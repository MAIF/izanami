package izanami.example.shows;

import io.vavr.collection.List;
import io.vavr.control.Option;


public interface Shows {

    List<ShowResume> search(String serie);

    Option<Show> get(String id);


    class ShowResume {
        public String id;
        public String image;
        public String title;
        public String description;

        public ShowResume() {
        }

        public ShowResume(String id, String title, String description, String image) {
            this.id = id;
            this.image = image;
            this.title = title;
            this.description = description;
        }
    }

    class Show {
        public String id;
        public String image;
        public String title;
        public String description;
        public List<Season> seasons;


        public Show() {
        }

        public Show(String id, String title, String description, String image, List<Season> season) {
            this.id = id;
            this.image = image;
            this.description = description;
            this.title = title;
            this.seasons = season;
        }

        public Show updateSeasons(List<Season> seasons) {
            return new Show(id, image, description, title, seasons);
        }

        @Override
        public String toString() {
            return "Show{" +
                    "id=" + id +
                    ", image='" + image + '\'' +
                    ", description='" + description + '\'' +
                    ", title='" + title + '\'' +
                    ", seasons=" + io.vavr.collection.List.ofAll(seasons).mkString("[",",", "]") +
                    '}';
        }
    }

    class Season {
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

    class Episode {
        public String id;
        public Long number;
        public Long seasonNumber;
        public String title;
        public String description;
        public Boolean watched = Boolean.FALSE;

        public Episode() {
        }

        public Episode(String id, Long number, Long seasonNumber, String title, String description, Boolean watched) {
            this.id = id;
            this.number = number;
            this.seasonNumber = seasonNumber;
            this.title = title;
            this.description = description;
            this.watched = watched;
        }

        public Episode updateWatched(Boolean watched) {
            return new Episode(id, number, seasonNumber, title, description, watched);
        }

        @Override
        public String toString() {
            return "Episode{" +
                    "id=" + id +
                    ", number=" + number +
                    ", seasonNumber=" + seasonNumber +
                    ", title='" + title + '\'' +
                    ", description='" + description + '\'' +
                    ", watched=" + watched +
                    '}';
        }
    }
}
