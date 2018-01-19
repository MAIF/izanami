package izanami.example.shows;

import io.vavr.collection.List;
import io.vavr.control.Option;
import izanami.example.shows.providers.betaserie.BetaSerieApi;
import izanami.example.shows.providers.tvdb.TvdbShowsApi;
import izanami.javadsl.FeatureClient;
import izanami.javadsl.Features;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;


@Primary
@Component
public class ShowsApi implements Shows {

    private final FeatureClient featureClient;

    private final TvdbShowsApi tvdbShowsApi;

    private final BetaSerieApi betaSerieApi;

    @Autowired
    public ShowsApi(FeatureClient featureClient, TvdbShowsApi tvdbShowsApi, BetaSerieApi betaSerieApi) {
        this.featureClient = featureClient;
        this.tvdbShowsApi = tvdbShowsApi;
        this.betaSerieApi = betaSerieApi;
    }

    @Override
    public List<ShowResume> search(String serie) {
        Features features = this.featureClient.features("mytvshows:providers:*").get();
        if (features.isActive("mytvshows:providers:tvdb")) {
            return tvdbShowsApi.search(serie);
        } else if (features.isActive("mytvshows:providers:betaserie")) {
            return betaSerieApi.search(serie);
        } else {
            return List.empty();
        }
    }

    @Override
    public Option<Show> get(String id) {
        Features features = this.featureClient.features("mytvshows:providers:*").get();
        if (features.isActive("mytvshows:providers:tvdb")) {
            return tvdbShowsApi.get(id);
        } else if (features.isActive("mytvshows:providers:betaserie")) {
            return betaSerieApi.get(id);
        } else {
            return Option.none();
        }
    }
}
