package app.pristinepage.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "Mask", foreignKeys = [ForeignKey(entity = SourceRow::class, parentColumns = ["sourceId"], childColumns = ["sourceId"], onDelete = ForeignKey.RESTRICT)], indices = [Index(value = ["relativePath"], unique = true), Index(value = ["sourceId", "kind"])])
data class MaskRow(@PrimaryKey val maskId: String, val sourceId: String, val kind: String, val sha256: String, val width: Int, val height: Int, val relativePath: String, val encodingVersion: Int, val refCount: Long, val state: String)

@Entity(tableName = "RecipeMask", primaryKeys = ["pageId", "recipeRevision", "maskId"], foreignKeys = [
    ForeignKey(entity = RecipeRevisionRow::class, parentColumns = ["pageId", "recipeRevision"], childColumns = ["pageId", "recipeRevision"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = MaskRow::class, parentColumns = ["maskId"], childColumns = ["maskId"], onDelete = ForeignKey.RESTRICT),
], indices = [Index(value = ["maskId"])])
data class RecipeMaskRow(val pageId: String, val recipeRevision: Long, val maskId: String, val role: String)

/** Polymorphic owners are checked by transactional repository commands/reconciliation, never inferred from filesystem presence or a cached counter. */
@Entity(tableName = "SourceReference", primaryKeys = ["ownerType", "ownerId", "sourceId"], foreignKeys = [ForeignKey(entity = SourceRow::class, parentColumns = ["sourceId"], childColumns = ["sourceId"], onDelete = ForeignKey.RESTRICT)], indices = [Index(value = ["sourceId"])])
data class SourceReferenceRow(val ownerType: String, val ownerId: String, val sourceId: String, val createdAt: Long)

@Entity(tableName = "MaskReference", primaryKeys = ["ownerType", "ownerId", "maskId"], foreignKeys = [ForeignKey(entity = MaskRow::class, parentColumns = ["maskId"], childColumns = ["maskId"], onDelete = ForeignKey.RESTRICT)], indices = [Index(value = ["maskId"])])
data class MaskReferenceRow(val ownerType: String, val ownerId: String, val maskId: String, val createdAt: Long)

@Entity(tableName = "AssetPin", primaryKeys = ["ownerType", "ownerId", "assetSha256"], indices = [Index(value = ["assetSha256"])])
data class AssetPinRow(val ownerType: String, val ownerId: String, val assetSha256: String, val providerId: String, val manifest: ByteArray)

@Entity(tableName = "RenderReference", primaryKeys = ["ownerType", "ownerId", "renderId"], foreignKeys = [ForeignKey(entity = RenderedPageRow::class, parentColumns = ["renderId"], childColumns = ["renderId"], onDelete = ForeignKey.RESTRICT)], indices = [Index(value = ["renderId"])])
data class RenderReferenceRow(val ownerType: String, val ownerId: String, val renderId: String, val createdAt: Long, val expiresAt: Long?)
