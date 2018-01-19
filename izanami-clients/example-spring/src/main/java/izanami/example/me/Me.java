package izanami.example.me;

import izanami.example.tvdb.TvdbApi;

import java.util.List;

public class Me {

    public String userId;
    public List<Tvshow> tvshows;

    public Me() {
    }

    public Me(String userId, List<Tvshow> tvshows) {
        this.userId = userId;
        this.tvshows = tvshows;
    }

    public Me updateTvShows(List<Tvshow> shows) {
        return new Me(userId, shows);
    }

    public static class Tvshow {
        public Long id;
        public String banner;
        public String overview;
        public String seriesName;
        public List<Season> seasons;

        public static Tvshow fromResume(TvdbApi.TvshowResume resume, List<Season> seasons) {
            return new Tvshow(
                    resume.id,
                    resume.banner,
                    resume.overview,
                    resume.seriesName,
                    seasons
            );
        }

        public Tvshow() {
        }

        public Tvshow(Long id, String banner, String overview, String seriesName, List<Season> seasons) {
            this.id = id;
            this.banner = banner;
            this.overview = overview;
            this.seriesName = seriesName;
            this.seasons = seasons;
        }

        public Tvshow updateSeasons(List<Season> seasons) {
            return new Tvshow(id, banner, overview, seriesName, seasons);
        }

        @Override
        public String toString() {
            return "Tvshow{" +
                    "id=" + id +
                    ", banner='" + banner + '\'' +
                    ", overview='" + overview + '\'' +
                    ", seriesName='" + seriesName + '\'' +
                    ", seasons=" + io.vavr.collection.List.ofAll(seasons).mkString("[",",", "]") +
                    '}';
        }
    }

    public static class Season {
        public Long number;
        public List<Episode> episodes;
        public Boolean allWatched;

        public Season() {
        }

        public Season(Long number, List<Episode> episodes) {
            this.number = number;
            this.episodes = episodes;
            this.allWatched = io.vavr.collection.List.ofAll(episodes).foldLeft(Boolean.TRUE, (acc, e) -> acc && e.watched);
        }

        public void setNumber(Long number) {
            this.number = number;
        }

        public void setEpisodes(List<Episode> episodes) {
            this.episodes = episodes;
            this.allWatched = io.vavr.collection.List.ofAll(episodes).foldLeft(Boolean.TRUE, (acc, e) -> acc && e.watched);
        }

        public void setAllWatched(Boolean allWatched) {
            this.allWatched = allWatched;
        }

        @Override
        public String toString() {
            return "Season{" +
                    "number=" + number +
                    ", episodes=" + io.vavr.collection.List.ofAll(episodes).mkString("[",",", "]") +
                    '}';
        }
    }

    public static class Episode {
        public Long id;
        public Long airedEpisodeNumber;
        public Long airedSeason;
        public Long airedSeasonID;
        public String episodeName;
        public String overview;
        public Boolean watched = Boolean.FALSE;


        static Episode fromResume(TvdbApi.EpisodeResume resume) {
            return new Episode(
                    resume.id,
                    resume.airedEpisodeNumber,
                    resume.airedSeason,
                    resume.airedSeasonID,
                    resume.episodeName,
                    resume.overview,
                    Boolean.FALSE
            );
        }

        public Episode() {
        }

        public Episode(Long id, Long airedEpisodeNumber, Long airedSeason, Long airedSeasonID, String episodeName, String overview, Boolean watched) {
            this.id = id;
            this.airedEpisodeNumber = airedEpisodeNumber;
            this.airedSeason = airedSeason;
            this.airedSeasonID = airedSeasonID;
            this.episodeName = episodeName;
            this.overview = overview;
            this.watched = watched;
        }

        Episode updateWatched(Boolean watched) {
            return new Episode(id, airedEpisodeNumber, airedSeason, airedSeasonID, episodeName, overview, watched);
        }

        @Override
        public String toString() {
            return "Episode{" +
                    "id=" + id +
                    ", airedEpisodeNumber=" + airedEpisodeNumber +
                    ", airedSeason=" + airedSeason +
                    ", airedSeasonID=" + airedSeasonID +
                    ", episodeName='" + episodeName + '\'' +
                    ", overview='" + overview + '\'' +
                    ", watched=" + watched +
                    '}';
        }
    }
}
