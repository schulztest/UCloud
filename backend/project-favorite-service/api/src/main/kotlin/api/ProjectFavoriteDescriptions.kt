package api

import dk.sdu.cloud.AccessRight
import dk.sdu.cloud.CommonErrorMessage
import dk.sdu.cloud.calls.CallDescriptionContainer
import dk.sdu.cloud.calls.auth
import dk.sdu.cloud.calls.bindEntireRequestFromBody
import dk.sdu.cloud.calls.call
import dk.sdu.cloud.calls.http
import dk.sdu.cloud.service.Page
import dk.sdu.cloud.service.WithPaginationRequest
import io.ktor.http.HttpMethod

data class ListFavoritesRequest(
    override val itemsPerPage: Int? = 10,
    override val page: Int? = 0
): WithPaginationRequest

typealias ListFavoritesResponse = Page<String>

data class ToggleFavoriteRequest(
    val projectID: String
)

typealias ToggleFavoriteResponse = Unit

object ProjectFavoriteDescriptions : CallDescriptionContainer("project.favorite") {
    val baseContext = "/api/project/favorite"

    val listFavorites = call<ListFavoritesRequest, ListFavoritesResponse, CommonErrorMessage>("listFavorites") {
        auth {
            access = AccessRight.READ
        }

        http {
            method = HttpMethod.Post

            path {
                using(baseContext)
                +"list"
            }

            body { bindEntireRequestFromBody() }
        }
    }

    val toggleFavorite = call<ToggleFavoriteRequest, ToggleFavoriteResponse, CommonErrorMessage>("toggleFavorite") {
        auth {
            access = AccessRight.READ
        }

        http {
            method = HttpMethod.Post

            path {
                using(baseContext)
            }

            body { bindEntireRequestFromBody() }
        }
    }
}
