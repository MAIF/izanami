package izanami.example.me;

import io.vavr.control.Option;

public interface MeRepository {

    Option<Me> get(String userId);
    Me save(Me me);
    void delete(String userId);
}
