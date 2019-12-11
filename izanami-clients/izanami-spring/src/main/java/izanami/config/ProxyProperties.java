package izanami.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@ConfigurationProperties(prefix = "izanami.proxy")
public class ProxyProperties {

    private Patterns feature;
    private Patterns config;
    private Patterns experiment;

    public Patterns getFeature() {
        return feature;
    }

    public void setFeature(Patterns feature) {
        this.feature = feature;
    }

    public Patterns getConfig() {
        return config;
    }

    public void setConfig(Patterns config) {
        this.config = config;
    }

    public Patterns getExperiment() {
        return experiment;
    }

    public void setExperiment(Patterns experiment) {
        this.experiment = experiment;
    }

    public static class Patterns {

        private List<String> patterns;

        public List<String> getPatterns() {
            return patterns;
        }

        public void setPatterns(List<String> patterns) {
            this.patterns = patterns;
        }
    }
}
