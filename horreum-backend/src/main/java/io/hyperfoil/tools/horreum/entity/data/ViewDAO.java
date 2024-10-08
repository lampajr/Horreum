package io.hyperfoil.tools.horreum.entity.data;

import java.util.Collections;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonIgnoreType;

import io.hyperfoil.tools.horreum.entity.CustomSequenceGenerator;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * Security model: the access to view is limited by access to the referenced test.
 */
@Entity(name = "view")
@Table(uniqueConstraints = @UniqueConstraint(columnNames = { "id", "name" }))
@JsonIgnoreType
public class ViewDAO extends PanacheEntityBase {
    @Id
    @CustomSequenceGenerator(name = "viewidgenerator", allocationSize = 1)
    public Integer id;

    @NotNull
    public String name;

    // In the future we could make this ManyToMany, but then we'd have to maintain
    // ownership and access in this entity separately.
    @ManyToOne(fetch = FetchType.LAZY)
    public TestDAO test;

    @NotNull
    @OneToMany(fetch = FetchType.EAGER, mappedBy = "view", orphanRemoval = true, cascade = CascadeType.ALL)
    @OrderBy("headerorder ASC")
    public List<ViewComponentDAO> components;

    public ViewDAO() {
    }

    public ViewDAO(String name, TestDAO test) {
        this.name = name;
        this.test = test;
        this.components = Collections.emptyList();
    }

    public void ensureLinked() {
        if (components != null) {
            for (ViewComponentDAO c : components) {
                if (c.id != null && c.id < 0) {
                    c.id = null;
                }
                c.view = this;
            }
        }
    }
}
