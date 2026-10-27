package com.microservice.product.dao;

import com.microservice.product.entity.Product;
import org.hibernate.SessionFactory;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class ProductDaoImpl implements IDao<Product> {
    private final SessionFactory sessionFactory;

    public ProductDaoImpl(SessionFactory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    @Override
    public void save(Product entity) {
        sessionFactory.getCurrentSession().save(entity);
    }

    @Override
    public void update(Product entity) {
        sessionFactory.getCurrentSession().update(entity);
    }

    @Override
    public void delete(Product entity) {
        sessionFactory.getCurrentSession().delete(entity);
    }

    @Override
    public Product findById(Long id) {
        return sessionFactory.getCurrentSession().get(Product.class, id);
    }

    @Override
    public List<Product> findAll() {
        return sessionFactory.getCurrentSession()
                .createQuery("from Product order by id", Product.class).getResultList();
    }
}
