package izanami.example.me;

import com.fasterxml.jackson.databind.ObjectMapper;
import izanami.example.me.impl.MeLevelDbRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.io.IOException;

@Configuration
public class MeConfiguration {

    @Bean
    @Autowired
    public MeRepository meRepository(ObjectMapper mapper, Environment environment) throws IOException {
        return new MeLevelDbRepository(environment.getRequiredProperty("leveldb.path"), mapper);
    }

}
