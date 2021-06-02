package io.hyperfoil.tools.horreum.entity.json;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.validation.constraints.NotNull;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

@Entity(name = "allowedhookprefix")
public class AllowedHookPrefix extends PanacheEntityBase {
   @Id
   @GeneratedValue
   public Long id;

   @NotNull
   public String prefix;
}
