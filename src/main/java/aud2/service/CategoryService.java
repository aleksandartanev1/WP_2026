package aud2.service;

import aud2.model.Category;

import java.util.List;

public interface CategoryService {
    List<Category> listAll();
    Category findById(Long id);
    Category create(String name, String description);
    void delete(Long id);
}
