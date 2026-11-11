package aud2.repository;

import aud2.bootstrap.DataHolder;
import aud2.model.Category;
import aud2.repository.impl.CategoryRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class InMemoryCategoryRepository implements CategoryRepository {

    @Override
    public List<Category> findAll() {
        return DataHolder.categories;
    }

    @Override
    public Optional<Category> findById(Long id) {
        return DataHolder.categories.stream()
                .filter(c -> c.getId().equals(id))
                .findFirst();
    }

    @Override
    public Category save(Category category) {
        DataHolder.categories.removeIf(c -> c.getId().equals(category.getId()));
        DataHolder.categories.add(category);
        return category;
    }

    @Override
    public void deleteById(Long id) {
        DataHolder.categories.removeIf(c -> c.getId().equals(id));
    }
}
