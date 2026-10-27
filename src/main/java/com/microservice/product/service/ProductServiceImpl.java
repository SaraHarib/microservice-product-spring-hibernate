package com.microservice.product.service;

import com.microservice.product.dao.IDao;
import com.microservice.product.entity.Product;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ProductServiceImpl implements ProductService {
    private final IDao<Product> productDao;

    public ProductServiceImpl(IDao<Product> productDao) {
        this.productDao = productDao;
    }

    @Override
    @Transactional
    public void addProduct(Product product) {
        validatePrice(product);
        productDao.save(product);
    }

    @Override
    @Transactional
    public void addProducts(List<Product> products) {
        // Une seule transaction : une erreur annule aussi les INSERT précédents.
        for (Product product : products) {
            validatePrice(product);
            productDao.save(product);
        }
    }

    @Override
    @Transactional
    public void updateProduct(Product product) {
        validatePrice(product);
        productDao.update(product);
    }

    @Override
    @Transactional
    public void deleteProduct(Product product) {
        productDao.delete(product);
    }

    @Override
    @Transactional(readOnly = true)
    public Product getProductById(Long id) {
        return productDao.findById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Product> getAllProducts() {
        return productDao.findAll();
    }

    private void validatePrice(Product product) {
        if (product.getPrice() < 0) {
            throw new IllegalArgumentException("Le prix ne peut pas être négatif");
        }
    }
}
