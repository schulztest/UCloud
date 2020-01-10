package dk.sdu.cloud.contact.book.services

import dk.sdu.cloud.calls.RPCException
import io.ktor.http.HttpStatusCode
import org.elasticsearch.action.admin.indices.flush.FlushRequest
import org.elasticsearch.action.bulk.BulkRequest
import org.elasticsearch.action.delete.DeleteRequest
import org.elasticsearch.action.index.IndexRequest
import org.elasticsearch.action.search.SearchRequest
import org.elasticsearch.client.RequestOptions
import org.elasticsearch.client.RestHighLevelClient
import org.elasticsearch.client.indices.CreateIndexRequest
import org.elasticsearch.common.xcontent.XContentType
import org.elasticsearch.index.query.QueryBuilders
import org.elasticsearch.search.SearchHit
import org.elasticsearch.search.SearchHits
import org.elasticsearch.search.builder.SearchSourceBuilder
import java.util.*
import kotlin.collections.HashMap

class ContactBookElasticDAO(private val elasticClient: RestHighLevelClient): ContactBookDAO {

    fun createIndex() {
        val request = CreateIndexRequest(CONTACT_BOOK_INDEX)
        request.settings("""
            {
            "number_of_shards": 2,
            "number_of_replicas": 2,
            "analysis": {
              "analyzer": "whitespace",
              "tokenizer": "whitespace"
            }
            }
            """.trimIndent(), XContentType.JSON)

        request.mapping("""
                {
                    "properties" : {
                        "fromUser" : {
                            "type" : "text",
                            "analyzer": "whitespace",
                            "fields" : {
                                "keyword" : { 
                                    "type" : "text",
                                    "analyzer" : "whitespace"
                                }
                            }
                        },
                        "toUser" : {
                            "type" : "text",
                            "analyzer": "whitespace",
                            "fields" : {
                                "keyword" : { 
                                    "type" : "text",
                                    "analyzer" : "whitespace"
                                }
                            }
                        },
                        "createdAt" : { 
                            "type" : "date"
                        },
                        "serviceOrigin" : {
                            "type" : "text",
                            "analyzer": "whitespace",
                            "fields" : {
                                "keyword" : { 
                                    "type" : "text",
                                    "analyzer" : "whitespace"
                                }
                            }
                        }
                    }
                }""".trimIndent(), XContentType.JSON)
        elasticClient.indices().create(request, RequestOptions.DEFAULT)
        elasticClient.indices().flush(FlushRequest(CONTACT_BOOK_INDEX).waitIfOngoing(true), RequestOptions.DEFAULT)
    }

    private fun createInsertContactRequest(fromUser: String, toUser: String, serviceOrigin: String): IndexRequest {
        val request = IndexRequest(CONTACT_BOOK_INDEX)
        val source = HashMap<String, Any>()
        source["fromUser"] = fromUser
        source["toUser"] = toUser
        source["createdAt"] = Date()
        source["serviceOrigin"] = serviceOrigin
        request.source(source)

        return request
    }

    override fun insertContact(fromUser: String, toUser: String, serviceOrigin: String) {
        val request = createInsertContactRequest(fromUser, toUser, serviceOrigin)
        elasticClient.index(request, RequestOptions.DEFAULT)
    }

    override fun insertContactsBulk(fromUser: String, toUser: List<String>, serviceOrigin: String) {
        val request = BulkRequest()
        toUser.forEach { shareReceiver ->
            val indexRequest = createInsertContactRequest(fromUser, shareReceiver, serviceOrigin)
            request.add(indexRequest)
        }
        elasticClient.bulk(request, RequestOptions.DEFAULT)
    }

    private fun findSingleContact(fromUser: String, toUser: String, serviceOrigin: String): SearchHit {
        val searchRequest = SearchRequest(CONTACT_BOOK_INDEX)
        val searchSource = SearchSourceBuilder().query(
            QueryBuilders.boolQuery()
                .must(
                    QueryBuilders.termQuery(
                        "fromUser", fromUser
                    )
                )
                .must(
                    QueryBuilders.termQuery(
                        "toUser", toUser
                    )
                )
                .must(
                    QueryBuilders.termQuery(
                        "serviceOrigin", serviceOrigin
                    )
                )
        )
        searchRequest.source(searchSource)

        val response = elasticClient.search(searchRequest, RequestOptions.DEFAULT)
        //Should be safe to use !!
        when {
            response.hits.totalHits!!.value.toInt() == 0 -> throw RPCException.fromStatusCode(HttpStatusCode.NotFound)
            response.hits.totalHits!!.value.toInt() > 1 -> throw RPCException.fromStatusCode(HttpStatusCode.Conflict)
            else -> return response.hits.hits.first()
        }
    }

    override fun deleteContact(fromUser: String, toUser: String, serviceOrigin: String) {
        val doc = findSingleContact(fromUser, toUser, serviceOrigin)
        val deleteRequest = DeleteRequest(CONTACT_BOOK_INDEX, doc.id)
        elasticClient.delete(deleteRequest, RequestOptions.DEFAULT)
    }

    override fun getAllContactsForUser(fromUser: String, serviceOrigin: String): SearchHits {
        val searchRequest = SearchRequest(CONTACT_BOOK_INDEX)
        val searchSource = SearchSourceBuilder().query(
            QueryBuilders.boolQuery()
                .must(
                    QueryBuilders.termQuery(
                        "fromUser", fromUser
                    )
                )
                .must(
                    QueryBuilders.termQuery(
                        "serviceOrigin", serviceOrigin
                    )
                )
        )
        searchRequest.source(searchSource)
        val response = elasticClient.search(searchRequest, RequestOptions.DEFAULT)
        return response.hits
    }

    override fun queryContacts(fromUser: String, query: String, serviceOrigin: String): SearchHits {
        val searchRequest = SearchRequest(CONTACT_BOOK_INDEX)
        val searchSource = SearchSourceBuilder().query(
            QueryBuilders.boolQuery()
                .must(
                    QueryBuilders.termQuery(
                        "fromUser", fromUser
                    )
                )
                .must(
                    QueryBuilders.wildcardQuery(
                        "toUser", query
                    )
                )
                .must(
                    QueryBuilders.termQuery(
                        "serviceOrigin", serviceOrigin
                    )
                )
        )
        searchRequest.source(searchSource)

        val response = elasticClient.search(searchRequest, RequestOptions.DEFAULT)
        return response.hits
    }

    companion object {
        const val CONTACT_BOOK_INDEX = "contactbook"
    }
}
