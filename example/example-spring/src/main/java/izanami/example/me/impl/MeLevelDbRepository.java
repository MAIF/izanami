package izanami.example.me.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vavr.control.Option;
import izanami.example.me.Me;
import izanami.example.me.MeRepository;

import java.io.Closeable;
import java.io.IOException;
import org.iq80.leveldb.*;
import static org.iq80.leveldb.impl.Iq80DBFactory.*;
import java.io.*;


public class MeLevelDbRepository implements MeRepository, Closeable {

    private final String path;
    private final DB db;
    private final ObjectMapper mapper;

    public MeLevelDbRepository(String path, ObjectMapper mapper) throws IOException {
        this.path = path;
        Options options = new Options();
        options.createIfMissing(true);
        this.db = factory.open(new File(path), options);
        this.mapper = mapper;
    }

    @Override
    public Option<Me> get(String userId) {
        try {
            byte[] src = db.get(bytes(userId));
            if (src == null) {
                return Option.none();
            } else {
                return Option.of(mapper.readValue(src, Me.class));
            }
        } catch (IOException e) {
            throw new RuntimeException("Error reading from db", e);
        }
    }

    @Override
    public Me save(Me me) {
        try {
            String rawMe = mapper.writeValueAsString(me);
            db.put(bytes(me.userId), bytes(rawMe));
            return me;
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Error writing to db", e);
        }
    }

    @Override
    public void delete(String userId) {
        db.delete(bytes(userId));
    }

    @Override
    public void close() throws IOException {
        if (this.db != null) {
            this.db.close();
        }
    }
}
