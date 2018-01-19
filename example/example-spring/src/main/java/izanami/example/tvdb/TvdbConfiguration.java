package izanami.example.tvdb;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestTemplate;

@Configuration
public class TvdbConfiguration {

    @Autowired
    Environment environment;

    @Autowired
    RestTemplate restTemplate;

    @Bean
    TvdbApi tvdbApi() {
        return new TvdbApi(
                restTemplate,
                environment.getProperty("tvdb.url"),
                new TvdbApi.Login(
                        environment.getProperty("tvdb.apikey")
                )
        );
    }
}
