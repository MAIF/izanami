package izanami.config;

import akka.actor.ActorSystem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PreDestroy;

@Configuration
public class IzanamiDestroy {

    @Autowired
    ActorSystem actorSystem;

    @PreDestroy
    public void destroy() {
        if (actorSystem != null) {
            actorSystem.terminate();
        }
    }


}
