package izanami.example.shows.providers.betaserie;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestTemplate;

@Configuration
public class BetaSerieConfiguration {

    @Autowired
    Environment environment;

    @Autowired
    RestTemplate restTemplate;

    @Bean
    BetaSerieApi betaSerieApi() {
        return new BetaSerieApi(
                environment.getProperty("betaserie.url"),
                environment.getProperty("betaserie.apikey"),
                restTemplate
        );
    }
}
