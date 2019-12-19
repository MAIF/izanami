package izanami.example;


import akka.actor.ActorSystem;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vavr.jackson.datatype.VavrModule;
import izanami.ClientConfig;
import izanami.Experiments;
import izanami.Strategies;
import izanami.example.otoroshi.OtoroshiFilter;
import izanami.javadsl.*;
import scala.concurrent.duration.FiniteDuration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.TimeUnit;

import javax.servlet.Filter;


@SpringBootApplication
public class Application {

    static final Logger LOGGER = LoggerFactory.getLogger(Application.class);

    @Autowired
    Environment environment;

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @Bean
    @Autowired
    RestTemplate restTemplate(RestTemplateBuilder builder, ObjectMapper objectMapper) {
        return builder.build();
    }

    @Bean
    Module vavrModule() {
        return new VavrModule();
    }

    @Configuration
    @Profile("otoroshi")
    static class Otoroshi {

        @Bean
        Filter otoroshiFilter(Environment environment) {
            String sharedKey = environment.getProperty("otoroshi.sharedKey");
            String issuer = environment.getProperty("otoroshi.issuer");
            String claimHeaderName = environment.getProperty("otoroshi.claimHeaderName");
            String requestIdHeaderName = environment.getProperty("otoroshi.requestIdHeaderName");
            String stateHeaderName = environment.getProperty("otoroshi.stateHeaderName");
            String stateRespHeaderName = environment.getProperty("otoroshi.stateRespHeaderName");
            return new OtoroshiFilter("prod", sharedKey, issuer, requestIdHeaderName, claimHeaderName, stateHeaderName, stateRespHeaderName);
        }
    }
}
