package com.microservice.product.service;

import com.microservice.product.entity.Product;

import java.util.List;

public interface ProductService {
    void addProduct(Product product);

    void addProducts(List<Product> products);

    void updateProduct(Product product);

    void deleteProduct(Product product);

    Product getProductById(Long id);

    List<Product> getAllProducts();
}
