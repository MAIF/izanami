package izanami.example.me;

import io.vavr.collection.List;
import izanami.example.shows.Shows;


public class Me {

    public String userId;
    public List<Shows.Show> shows;

    public Me() {
    }

    public Me(String userId, List<Shows.Show> shows) {
        this.userId = userId;
        this.shows = shows;
    }

    public Me updateTvShows(List<Shows.Show> shows) {
        return new Me(userId, shows);
    }

}
