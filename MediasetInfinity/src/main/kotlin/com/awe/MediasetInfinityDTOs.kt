package com.awe
import com.fasterxml.jackson.annotation.JsonProperty

data class InertiaResponse(
    @JsonProperty("data") val data: Data
)

data class Data(
    @JsonProperty("getSearchPage") val getSearchPage: GetSearchPage
)

data class GetSearchPage(
    @JsonProperty("areaContainersConnection") val areaContainersConnection: AreaContainersConnection
)

data class AreaContainersConnection(
    @JsonProperty("areaContainers") val areaContainers: List<AreaContainer>
)

data class AreaContainer(
    @JsonProperty("areas") val areas: List<Area>
)

data class Area(
    @JsonProperty("sections") val sections: List<Section>
)

data class Section(
    @JsonProperty("collections") val collections: List<MediasetCollection>
)

data class MediasetCollection(
    @JsonProperty("itemsConnection") val itemsConnection: MediasetItems
)

data class MediasetItems(
    @JsonProperty("items") val items: List<Item>
)

data class Item(
    @JsonProperty("cardLink") val card: CardLink?,
    @JsonProperty("guid") val guid: String?,
    @JsonProperty("cardTitle") val name: String?,
    @JsonProperty("__typename") val type: String?
)

data class CardLink(
    @JsonProperty("value") val url: String?
)

data class VideoGraphQLResponse(
    val data: VideoData? = null
)

data class VideoData(
    val getVideoPage: GetVideoPage? = null
)

data class GetVideoPage(
    val cdnUrl: String? = null,
    val mediaInfo: MediaInfo? = null,
    val areaContainersConnection: VideoAreaContainers? = null
)

data class VideoAreaContainers(
    val areaContainers: List<VideoAreaContainer>? = null
)

data class VideoAreaContainer(
    val areas: List<VideoArea>? = null
)

data class VideoArea(
    val sections: List<VideoSection>? = null
)

data class VideoSection(
    val collections: List<VideoCollection>? = null
)

data class VideoCollection(
    val itemsConnection: VideoItemsConnection? = null
)

data class VideoItemsConnection(
    val items: List<VideoItem>? = null
)

data class VideoItem(
    val guid: String? = null,
    val mediaInfo: MediaInfo? = null
)

data class MediaInfo(
    val streamingUrl: String? = null,
    val publicUrl: String? = null,
    val titleEpisode: String? = null
)

data class ApiEpisode(
    val id: String,
    val title: String,
    val posterUrl: String?,
    val episodeNumber: Int?,
    val description: String?
)