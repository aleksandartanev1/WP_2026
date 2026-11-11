package aud2.service.impl;

import aud2.model.Category;
import aud2.repository.impl.CategoryRepository;
import aud2.service.CategoryService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CategoryServiceImpl implements CategoryService {

    private final CategoryRepository categoryRepository;

    // Dependency Injection: Spring го дава репозиториумот овде
    public CategoryServiceImpl(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    @Override
    public List<Category> listAll() {
        return categoryRepository.findAll();
    }

    @Override
    public Category findById(Long id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Категоријата не постои"));
    }

    @Override
    public Category create(String name, String description) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Името не смее да биде празно");
        }
        return categoryRepository.save(new Category(name, description));
    }

    @Override
    public void delete(Long id) {
        categoryRepository.deleteById(id);
    }
}
