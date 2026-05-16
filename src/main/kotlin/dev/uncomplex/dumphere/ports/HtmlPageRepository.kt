package dev.uncomplex.dumphere.ports

import dev.uncomplex.dumphere.application.HtmlPageEntity
import org.springframework.data.repository.CrudRepository

interface HtmlPageRepository : CrudRepository<HtmlPageEntity, String>
