package izanami.config;

import akka.actor.ActorSystem;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnMissingBean(ActorSystem.class)
public class AkkaConfig {

    @Bean
    ActorSystem actorSystem() {
        return ActorSystem.create();
    }
}
