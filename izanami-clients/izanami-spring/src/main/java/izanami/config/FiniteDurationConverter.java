package izanami.config;

import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;

@Component
@ConfigurationPropertiesBinding
public class FiniteDurationConverter implements Converter<String, FiniteDuration> {

    @Override
    public FiniteDuration convert(String source) {
        return (FiniteDuration)FiniteDuration.create(source);
    }
}
