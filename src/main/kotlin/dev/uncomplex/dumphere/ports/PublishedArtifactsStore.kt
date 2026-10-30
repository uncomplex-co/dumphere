package dev.uncomplex.dumphere.ports

import dev.uncomplex.dumphere.application.ArtifactVisibility
import dev.uncomplex.dumphere.application.PageContentFormat
import dev.uncomplex.dumphere.application.PublishedPage
import dev.uncomplex.dumphere.application.UserEntity

interface PublishedArtifactsStore {
    fun publish(title: String?, contents: String, contentFormat: PageContentFormat, createdBy: UserEntity?): PublishedPage

    fun readContents(id: String): String?

    fun readMetadata(id: String): PublishedPage?

    fun update(id: String, contents: String, updatedBy: UserEntity?): PublishedPage?

    fun edit(id: String, oldString: String, newString: String, replaceAll: Boolean, updatedBy: UserEntity?): PublishedPage?

    fun setLive(id: String, isLive: Boolean, updatedBy: UserEntity?): PublishedPage?

    fun setVisibility(id: String, visibility: ArtifactVisibility, updatedBy: UserEntity?): PublishedPage?

    fun setCustomMetadata(id: String, key: String, value: Any?, updatedBy: UserEntity?): PublishedPage?
}
