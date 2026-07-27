package com.example.demo.monitoring;

/** Private, token-protected browser console served through the admin route. */
final class ConsolePage {
	private ConsolePage() { }

	static String html() {
		return """
			<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
			<title>Bot 控制台</title><style>
			:root{color-scheme:dark;font-family:system-ui,-apple-system,"Microsoft YaHei",sans-serif;background:#07121f;color:#eef7ff}*{box-sizing:border-box}body{margin:0;padding:28px;background:radial-gradient(circle at top right,#123d42,#07121f 55%);min-height:100vh}main{max-width:1100px;margin:auto}h1{font-size:34px;margin:0 0 6px}.muted{color:#9bb0c5}.bar,.panel{background:#0d1b2b;border:1px solid #26445a;border-radius:16px;padding:18px;margin-top:18px;box-shadow:0 14px 50px #0004}.bar{display:flex;gap:10px;flex-wrap:wrap}input,select,button{font:inherit;border-radius:10px;border:1px solid #36546d;padding:11px 13px;background:#08131f;color:#eef7ff}input{min-width:260px;flex:1}button{cursor:pointer;background:#38d6bd;color:#06221d;font-weight:700}button.secondary{background:#172a3d;color:#dcecff}button.danger{background:#542430;color:#ffdbe1}.grid{display:grid;grid-template-columns:340px 1fr;gap:18px}.files{display:grid;gap:9px}.file{display:flex;gap:10px;align-items:center;justify-content:space-between;padding:12px;border:1px solid #29465d;border-radius:11px;background:#081523;overflow-wrap:anywhere}.file small{color:#9bb0c5}.upload{display:flex;gap:10px;flex-wrap:wrap;margin:14px 0}.upload input{min-width:0}.log{min-height:420px;max-height:65vh;overflow:auto;white-space:pre-wrap;background:#050a11;border:1px solid #263e54;border-radius:12px;padding:14px;color:#c6e5d4;font:12px/1.55 ui-monospace,Consolas,monospace}@media(max-width:760px){body{padding:14px}.grid{grid-template-columns:1fr}h1{font-size:28px}}
			</style></head><body><main><h1>Bot 控制台</h1><p class="muted">按账号管理知识库，并查看 demo-assistant 最近 24 小时运行日志。</p>
			<div class="bar"><input id="token" type="password" placeholder="ADMIN_API_TOKEN" autocomplete="off"><button id="load">加载账号</button><button class="secondary" id="openAccounts">扫码账号管理</button><span id="notice" class="muted"></span></div>
			<div class="grid"><section class="panel"><h2>账号知识库</h2><p class="muted">选择一个 Bot；它在微信内检索时只会读取自己的文件。</p><select id="account"><option>请先加载账号</option></select><div class="upload"><input id="file" type="file" accept=".pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.txt,.md,.csv,.json,.yaml,.yml"><button id="upload">上传并建立索引</button></div><div id="files" class="files"></div></section>
			<section class="panel"><h2>最近 24 小时日志</h2><p class="muted">仅显示当前 demo-assistant 服务日志；不会展示管理令牌。</p><div class="upload"><button id="logs">刷新日志</button><button class="secondary" id="download">下载 .log</button></div><pre id="log" class="log">点击“刷新日志”查看。</pre></section></div></main>
			<script>
			const base='/api/admin/ilink',q=s=>document.querySelector(s),notice=q('#notice'),token=q('#token'),account=q('#account'),files=q('#files'),log=q('#log');let latestLog='';
			const headers=()=>({'X-Admin-Token':token.value.trim()});
			async function request(url,opt={}){opt.headers={...headers(),...(opt.headers||{})};const r=await fetch(url,opt);if(!r.ok)throw new Error((await r.text())||('HTTP '+r.status));return r}
			function msg(text){notice.textContent=text}
			async function loadAccounts(){const data=await (await request(base+'/accounts')).json();account.replaceChildren();for(const item of data){const o=document.createElement('option');o.value=item.accountId;o.textContent=(item.botId||item.accountId)+' · '+item.status;account.append(o)}await loadFiles();msg('已加载 '+data.length+' 个账号')}
			async function loadFiles(){if(!account.value)return;const data=await (await request(base+'/accounts/'+encodeURIComponent(account.value)+'/knowledge')).json();files.replaceChildren();if(!data.length){files.textContent='此账号还没有知识库文件。';return}for(const item of data){const row=document.createElement('div');row.className='file';const text=document.createElement('div');text.textContent=item.name;const meta=document.createElement('small');meta.textContent=(item.size/1024).toFixed(1)+' KB';text.append(document.createElement('br'),meta);const del=document.createElement('button');del.className='danger';del.textContent='删除';del.onclick=async()=>{if(confirm('删除 '+item.name+'？')){await request(base+'/accounts/'+encodeURIComponent(account.value)+'/knowledge/'+encodeURIComponent(item.name),{method:'DELETE'});loadFiles()}};row.append(text,del);files.append(row)}}
			async function upload(){const selected=q('#file').files[0];if(!selected)throw new Error('请先选择文件');const form=new FormData();form.append('file',selected);await request(base+'/accounts/'+encodeURIComponent(account.value)+'/knowledge',{method:'POST',body:form});q('#file').value='';await loadFiles();msg('文件已加入该账号知识库')}
			async function loadLogs(){latestLog=await (await request(base+'/logs?hours=24')).text();log.textContent=latestLog||'最近 24 小时没有日志。';msg('日志已刷新')}
			q('#load').onclick=()=>loadAccounts().catch(e=>msg('加载失败：'+e.message));q('#upload').onclick=()=>upload().catch(e=>msg('上传失败：'+e.message));account.onchange=()=>loadFiles().catch(e=>msg('读取失败：'+e.message));q('#logs').onclick=()=>loadLogs().catch(e=>msg('日志读取失败：'+e.message));q('#download').onclick=()=>{const a=document.createElement('a');a.href=URL.createObjectURL(new Blob([latestLog],{type:'text/plain'}));a.download='demo-assistant-last-24h.log';a.click();URL.revokeObjectURL(a.href)};q('#openAccounts').onclick=()=>location.href=base+'/login';
			</script></body></html>
			""";
	}
}
