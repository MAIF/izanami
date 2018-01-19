package izanami.example.shows.providers.tvdb;

import izanami.example.shows.providers.tvdb.TvdbShowsApi;
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
    TvdbShowsApi tvdbApi() {
        return new TvdbShowsApi(
                restTemplate,
                environment.getProperty("tvdb.url"),
                environment.getProperty("tvdb.banner"),
                new TvdbShowsApi.Login(
                        environment.getProperty("tvdb.apikey")
                )
        );
    }
}
