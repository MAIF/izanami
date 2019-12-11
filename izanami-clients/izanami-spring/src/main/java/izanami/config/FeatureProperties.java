package izanami.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import javax.validation.constraints.NotNull;

@Configuration
@ConfigurationProperties(prefix = "izanami.feature")
public class FeatureProperties {

    @NotNull
    private StrategyProperties strategy;
    private String fallback;
    private Boolean autocreate = false;

    public StrategyProperties getStrategy() {
        return strategy;
    }

    public void setStrategy(StrategyProperties strategy) {
        this.strategy = strategy;
    }

    public String getFallback() {
        return fallback;
    }

    public void setFallback(String fallback) {
        this.fallback = fallback;
    }

    public Boolean getAutocreate() {
        return autocreate;
    }

    public void setAutocreate(Boolean autocreate) {
        this.autocreate = autocreate;
    }
}
