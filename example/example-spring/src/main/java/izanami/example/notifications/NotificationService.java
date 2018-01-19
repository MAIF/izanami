package izanami.example.notifications;

import io.vavr.collection.List;
import izanami.javadsl.ConfigClient;
import izanami.javadsl.FeatureClient;
import izanami.javadsl.Registration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;

@Component
public class NotificationService {

    private final static Logger LOGGER = LoggerFactory.getLogger(NotificationService.class);

    private final FeatureClient featureClient;
    private final ConfigClient configClient;
    private final Registration registration;

    private final String ACTIVATION_FEATURE_NAME = "izanami:example:emailNotifications";
    private final String EMAIL_PROVIDER_CONFIG = "izanami:example:config";

    private Boolean notificationEnabled = Boolean.FALSE;

    @Autowired
    public NotificationService(FeatureClient featureClient, ConfigClient configClient) {
        this.featureClient = featureClient;
        this.configClient = configClient;

        if (this.featureClient.checkFeature(ACTIVATION_FEATURE_NAME).get()) {
            LOGGER.info("Email notification is enabled on start");
            startNotification();
        } else {
            LOGGER.info("Email notification is disabled on start");
        }

        this.registration = this.featureClient.onFeatureChanged(ACTIVATION_FEATURE_NAME, feature -> {
            if (feature.enabled()) {
                LOGGER.info("Email notification enabled");
                this.startNotification();
            } else {
                LOGGER.info("Email notification disabled");
                this.stopNotification();
            }
        });
    }

    private void startNotification() {
        this.notificationEnabled = Boolean.TRUE;
    }

    private void stopNotification() {
        this.notificationEnabled = Boolean.FALSE;
    }

    public void sendNotification(java.util.List<String> emails, String message) {
        if (notificationEnabled && emails != null) {
            configClient.config(EMAIL_PROVIDER_CONFIG).onSuccess(json -> {
                String emailProvider = json.field("emailProvider").asString();
                LOGGER.info("Sending email to {} with message \"{}\" using provider {}", List.ofAll(emails).mkString(", "), message, emailProvider);
            });
        }

    }

    @PreDestroy
    public void onStop() {
        this.registration.close();
    }
}
