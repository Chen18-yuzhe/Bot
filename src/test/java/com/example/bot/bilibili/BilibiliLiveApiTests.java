package com.example.bot.bilibili;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;

class BilibiliLiveApiTests {

	@Test
	@SuppressWarnings("unchecked")
	void readsFoldersForTheCookieOwner() throws Exception {
		HttpClient client = mock(HttpClient.class);
		HttpResponse<String> response = mock(HttpResponse.class);
		when(response.statusCode()).thenReturn(200);
		when(response.body()).thenReturn("""
				{"code":0,"data":{"list":[{"id":2769950940,"title":"默认收藏夹"}]}}
				""");
		when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
				.thenReturn(response);
		BilibiliLiveApi api = new BilibiliLiveApi(client, new ObjectMapper());

		var folders = api.listFolders("SESSDATA=secret; DedeUserID=12345; bili_jct=csrf");

		assertThat(folders).containsEntry(2769950940L, "默认收藏夹");
		ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
		verify(client).send(request.capture(), any(HttpResponse.BodyHandler.class));
		assertThat(request.getValue().uri().toString()).endsWith("up_mid=12345");
	}

	@Test
	@SuppressWarnings("unchecked")
	void returnsOnlyTheLiveVideosInApiOrder() throws Exception {
		HttpClient client = mock(HttpClient.class);
		HttpResponse<String> response = mock(HttpResponse.class);
		when(response.statusCode()).thenReturn(200);
		when(response.body()).thenReturn("""
				{"code":0,"data":{"has_more":false,"medias":[
				  {"bvid":"BV1LIVE00001","title":"机器学习课程","intro":"课程简介",
				   "duration":1312,"upper":{"name":"期末加油站","mid":1}},
				  {"bvid":"BV1LIVE00002","title":"语言替换工具演示","intro":"演示",
				   "duration":156,"upper":{"name":"BK_201","mid":2}}
				]}}
				""");
		when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
				.thenReturn(response);
		BilibiliLiveApi api = new BilibiliLiveApi(client, new ObjectMapper());

		var videos = api.listFolderVideos(
				"SESSDATA=secret; DedeUserID=12345", 2769950940L);

		assertThat(videos).extracting(BilibiliLiveApi.LiveVideo::title)
				.containsExactly("机器学习课程", "语言替换工具演示");
		assertThat(videos).extracting(BilibiliLiveApi.LiveVideo::folderId)
				.containsOnly(2769950940L);
	}

	@Test
	@SuppressWarnings("unchecked")
	void readsAndDeduplicatesDanmakuForTheVideoCid() throws Exception {
		HttpClient client = mock(HttpClient.class);
		HttpResponse<String> viewResponse = mock(HttpResponse.class);
		HttpResponse<String> danmakuResponse = mock(HttpResponse.class);
		when(viewResponse.statusCode()).thenReturn(200);
		when(viewResponse.body()).thenReturn("{\"code\":0,\"data\":{\"cid\":12345}}");
		when(danmakuResponse.statusCode()).thenReturn(200);
		when(danmakuResponse.body()).thenReturn("<i><d p=\"1,1,25,16777215\">讲得清楚</d><d p=\"2,1,25,16777215\">讲得清楚</d><d p=\"3,1,25,16777215\">收藏了 &amp; 感谢</d></i>");
		when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
				.thenReturn(viewResponse, danmakuResponse);
		BilibiliLiveApi api = new BilibiliLiveApi(client, new ObjectMapper());

		var danmaku = api.danmaku("SESSDATA=secret; DedeUserID=12345", "BV1test12345");

		assertThat(danmaku.totalComments()).isEqualTo(3);
		assertThat(danmaku.samples()).containsExactly("讲得清楚", "收藏了 & 感谢");
		ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
		verify(client, org.mockito.Mockito.times(2)).send(request.capture(), any(HttpResponse.BodyHandler.class));
		assertThat(request.getAllValues()).extracting(value -> value.uri().toString())
				.containsExactly("https://api.bilibili.com/x/web-interface/view?bvid=BV1test12345",
						"https://api.bilibili.com/x/v1/dm/list.so?oid=12345");
	}
}
