package dk.sdu.cloud.avatar.services

import dk.sdu.cloud.avatar.api.Avatar
import dk.sdu.cloud.avatar.api.Clothes
import dk.sdu.cloud.avatar.api.ClothesGraphic
import dk.sdu.cloud.avatar.api.ColorFabric
import dk.sdu.cloud.avatar.api.Eyebrows
import dk.sdu.cloud.avatar.api.Eyes
import dk.sdu.cloud.avatar.api.FacialHair
import dk.sdu.cloud.avatar.api.FacialHairColor
import dk.sdu.cloud.avatar.api.HairColor
import dk.sdu.cloud.avatar.api.MouthTypes
import dk.sdu.cloud.avatar.api.SkinColors
import dk.sdu.cloud.avatar.api.Top
import dk.sdu.cloud.avatar.api.TopAccessory
import dk.sdu.cloud.service.db.HibernateEntity
import dk.sdu.cloud.service.db.HibernateSession
import dk.sdu.cloud.service.db.WithId
import dk.sdu.cloud.service.db.criteria
import dk.sdu.cloud.service.db.get
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.EnumType
import javax.persistence.Enumerated
import javax.persistence.Id
import javax.persistence.Table

@Entity
@Table(name = "avatars")
class AvatarEntity(
    @Column
    @Id
    var username: String,

    @Enumerated(EnumType.STRING)
    var top: Top,

    @Enumerated(EnumType.STRING)
    var topAccessory: TopAccessory,

    @Enumerated(EnumType.STRING)
    var hairColor: HairColor,

    @Enumerated(EnumType.STRING)
    var facialHair: FacialHair,

    @Enumerated(EnumType.STRING)
    var facialHairColor: FacialHairColor,

    @Enumerated(EnumType.STRING)
    var clothes: Clothes,

    @Enumerated(EnumType.STRING)
    var colorFabric: ColorFabric,

    @Enumerated(EnumType.STRING)
    var eyes: Eyes,

    @Enumerated(EnumType.STRING)
    var eyebrows: Eyebrows,

    @Enumerated(EnumType.STRING)
    var mouthTypes: MouthTypes,

    @Enumerated(EnumType.STRING)
    var skinColors: SkinColors,

    @Enumerated(EnumType.STRING)
    var clothesGraphic: ClothesGraphic
) {
    companion object : HibernateEntity<AvatarEntity>, WithId<String>
}

private fun defaultAvatar(user: String): Avatar =
    Avatar(
        user,
        Top.HAT,
        TopAccessory.BLANK,
        HairColor.BLACK,
        FacialHair.BLANK,
        FacialHairColor.BLACK,
        Clothes.SHIRT_CREW_NECK,
        ColorFabric.BLACK,
        Eyes.SURPRISED,
        Eyebrows.DEFAULT,
        MouthTypes.SMILE,
        SkinColors.YELLOW,
        ClothesGraphic.BEAR
    )


fun AvatarEntity.toModel(): Avatar = Avatar(
    username,
    top,
    topAccessory,
    hairColor,
    facialHair,
    facialHairColor,
    clothes,
    colorFabric,
    eyes,
    eyebrows,
    mouthTypes,
    skinColors,
    clothesGraphic
)

fun Avatar.toEntity(): AvatarEntity = AvatarEntity(
    user,
    top,
    topAccessory,
    hairColor,
    facialHair,
    facialHairColor,
    clothes,
    colorFabric,
    eyes,
    eyebrows,
    mouthTypes,
    skinColors,
    clothesGraphic
)

class AvatarHibernateDAO: AvatarDAO<HibernateSession>{

    override fun upsert(
        session: HibernateSession,
        user: String,
        avatar: Avatar
    ) {
        val foundAvatar = findInternal(session, user)
        if (foundAvatar != null) {
            foundAvatar.top = avatar.top
            foundAvatar.topAccessory = avatar.topAccessory
            foundAvatar.hairColor = avatar.hairColor
            foundAvatar.facialHair = avatar.facialHair
            foundAvatar.facialHairColor = avatar.facialHairColor
            foundAvatar.clothes = avatar.clothes
            foundAvatar.colorFabric = avatar.colorFabric
            foundAvatar.eyes = avatar.eyes
            foundAvatar.eyebrows = avatar.eyebrows
            foundAvatar.mouthTypes = avatar.mouthTypes
            foundAvatar.skinColors = avatar.skinColors
            foundAvatar.clothesGraphic = avatar.clothesGraphic
            session.update(foundAvatar)
        } else {
            val entity = avatar.toEntity()
            session.save(entity)
        }
    }

    private fun findInternal(
        session: HibernateSession,
        user: String
    ): AvatarEntity? {
        return session.criteria<AvatarEntity> {
            (entity[AvatarEntity::username] equal user)
        }.uniqueResult()
    }

    override fun findByUser(
        session: HibernateSession,
        user: String
    ): Avatar {
        return session.criteria<AvatarEntity> {
            (entity[AvatarEntity::username] equal user)
        }.uniqueResult()?.toModel() ?: defaultAvatar(user)
    }
}
