package aud2.bootstrap;

import aud2.model.Category;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;

@Component
public class DataHolder {
    public static final List<Category> categories = new ArrayList<>();

    @PostConstruct
    public void init() {
        categories.add(new Category("Книги", "Сите видови книги"));
        categories.add(new Category("Филмови", "Филмови и серии"));
    }
}
