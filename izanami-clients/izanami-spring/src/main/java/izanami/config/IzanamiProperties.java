package izanami.config;


import org.hibernate.validator.constraints.URL;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Pattern;
import java.time.ZoneId;

@Configuration
@ConfigurationProperties(prefix = "izanami")
public class IzanamiProperties {

    @URL
    @NotEmpty
    private String host;
    @NotEmpty
    private String clientId;
    @NotEmpty
    private String clientSecret;
    private String clientIdHeaderName = "Izanami-Client-Id";
    private String clientSecretHeaderName = "Izanami-Client-Secret";
    @Pattern(regexp = "(SseBackend|Undefined|PollingBackend)")
    private String backend = "Undefined";
    private Integer pageSize = 200;
    private ZoneId zoneId = ZoneId.of("Europe/Paris");
    private String dispatcher = "akka.actor.default-dispatcher";

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public String getClientIdHeaderName() {
        return clientIdHeaderName;
    }

    public void setClientIdHeaderName(String clientIdHeaderName) {
        this.clientIdHeaderName = clientIdHeaderName;
    }

    public String getClientSecretHeaderName() {
        return clientSecretHeaderName;
    }

    public void setClientSecretHeaderName(String clientSecretHeaderName) {
        this.clientSecretHeaderName = clientSecretHeaderName;
    }

    public String getBackend() {
        return backend;
    }

    public void setBackend(String backend) {
        this.backend = backend;
    }

    public Integer getPageSize() {
        return pageSize;
    }

    public void setPageSize(Integer pageSize) {
        this.pageSize = pageSize;
    }

    public ZoneId getZoneId() {
        return zoneId;
    }

    public void setZoneId(ZoneId zoneId) {
        this.zoneId = zoneId;
    }

    public String getDispatcher() {
        return dispatcher;
    }

    public void setDispatcher(String dispatcher) {
        this.dispatcher = dispatcher;
    }
}
