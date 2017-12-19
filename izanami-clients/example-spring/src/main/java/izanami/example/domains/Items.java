package izanami.example.domains;

import io.vavr.collection.HashMap;
import io.vavr.collection.List;
import io.vavr.collection.Map;
import io.vavr.control.Option;
import org.springframework.stereotype.Component;


@Component
public class Items {

    private Map<String, Map<String, Item>> items = HashMap.empty();

    public List<Item> listItems(String username) {
        return items.get(username).toList().flatMap(Map::values);
    }

    public Option<Item> getItem(String username, String name) {
        return items.get(username).flatMap(m -> m.get(name));
    }

    public Item saveItem(String username, Item item) {
        Map<String, Item> todos = this.items.get(username).getOrElse(HashMap.empty());
        this.items = this.items.put(username, todos.put(item.name, item));
        return item;
    }

    public void deleteItem(String username, String name) {
        Map<String, Item> orElse = items.get(username).map(m -> m.remove(name)).getOrElse(HashMap.empty());
        items = items.put(username, orElse);
    }

    public void deleteAllItems(String username, Boolean done) {
        if (done) {
            Map<String, Item> values = items.get(username)
                    .map(l -> l.filter(t -> !done.equals(t._2.done)))
                    .getOrElse(HashMap.empty());
            items = items.put(username, values);
        } else {
            items = items.put(username, HashMap.empty());
        }
    }

    public static class Item {

        public String name;
        public String description;
        public Boolean done;

        public Item() {
        }

        public Item(String name, String description, Boolean done) {
            this.name = name;
            this.description = description;
            this.done = done;
        }
    }
}
