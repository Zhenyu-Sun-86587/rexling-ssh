//! Read-only status snapshots. This connection never opens PTY data or takes ownership.
use anyhow::{Context,Result};
use rex_core::{control::{Control,Trace},transport::Endpoint};
use serde_json::{Value,json};
use std::time::Duration;

fn plain(value: &Value,limit: usize)->String {
    value.as_str().unwrap_or("").chars().filter(|c|!c.is_control() && !matches!(*c,'\u{202a}'..='\u{202e}'|'\u{2066}'..='\u{2069}')).take(limit).collect()
}
pub fn records(process: &Value,status: &Value,title: &Value,session: &Value,window: &Value,block: &Value)->Vec<Value> {
    status["records"].as_array().into_iter().flatten().filter_map(|record| {
        let state=record["state"].as_str()?;
        if !matches!(state,"working"|"blocked"|"done"|"error"|"idle") {return None;}
        // Completed/error reports intentionally survive process exit. Live states
        // with a known owner must still belong to the foreground process.
        let owner=&record["owner"];let foreground=&process["foreground"];
        if !matches!(state,"done"|"error") && owner.is_object() &&
            (owner["pid"]!=foreground["pid"] || owner["name"]!=foreground["name"]) {return None;}
        let app=record["effective_app"].as_str().or(record["app"].as_str()).or(owner["name"].as_str()).unwrap_or("");
        let label=plain(&record["title"],256);
        let root=record["id"].as_str().is_none_or(str::is_empty);
        let live=if root && owner["pid"].is_number() && owner["pid"]==foreground["pid"] && owner["name"]==foreground["name"] {super::engine::program_title(process,status,title)} else {String::new()};
        let label=if !live.is_empty() {live} else {label};
        Some(json!({"session":session["session_id"],"sessionLabel":plain(&session["label"],256),
            "window":window["window_id"],"windowLabel":plain(&window["label"],256),
            "block":block["block_id"],"blockLabel":plain(&block["label"],256),
            "id":plain(&record["id"],128),"app":app,"owner":plain(&owner["name"],64),"ownerPid":owner["pid"],"reportedTitle":plain(&record["title"],256),
            "title":label,"message":plain(&record["msg"],2048),"state":state,"kind":plain(&record["kind"],32),
            "progress":record["progress"].as_u64().filter(|p|*p<=100),"updatedAt":plain(&record["updated_at"],64)}))
    }).collect()
}
/// Only the root report of this exact process follows the terminal's OSC title.
pub fn apply_title(items: &mut Value,session: &str,block: &str,event: &Value) {
    let Some(records)=items.as_array_mut() else {return;};
    for record in records {
        if record["session"]==session && record["block"]==block && record["id"].as_str().is_none_or(str::is_empty)
            && event["owner"]["pid"].is_number() && record["ownerPid"]==event["owner"]["pid"] && record["owner"]==event["owner"]["name"] {
            let title=plain(&event["title"],256);
            record["title"]=if title.is_empty() {record["reportedTitle"].clone()} else {json!(title)};
        }
    }
}
pub async fn scan(endpoint: Endpoint)->Result<Value> {
    tokio::time::timeout(Duration::from_secs(15),async {
        let mut control=Control::connect(endpoint,None,Trace::default()).await?;
        control.call("client.set_label",json!({"label":"rex-android-activity"})).await?;
        let sessions=control.call("session.list",json!({})).await?["sessions"].clone();
        let mut items=Vec::new();let mut panes=0;let mut truncated=false;let mut incomplete=false;
        let deadline=tokio::time::Instant::now()+Duration::from_secs(10);
        for (index,session) in sessions.as_array().into_iter().flatten().enumerate() {
            if index>=64 || panes>=256 || tokio::time::Instant::now()>deadline {truncated=true;break;}
            let Some(sid)=session["session_id"].as_str() else {continue;};
            let attached=match control.attach(sid).await {Ok(v)=>v,Err(_)=>{incomplete=true;continue;}};
            for window in attached["view"]["windows"].as_array().into_iter().flatten() {
                for block in window["layers"].as_array().into_iter().flatten().flat_map(|layer|layer["blocks"].as_array().into_iter().flatten()) {
                    if block["creator_name"]!="com.superlogical.terminal" {continue;}
                    if panes>=256 || items.len()>=1024 || tokio::time::Instant::now()>deadline {truncated=true;break;}
                    panes+=1;
                    let Some(id)=block["block_id"].as_str() else {continue;};
                    let status=match control.terminal(sid,id,"program_status",json!({})).await {Ok(v)=>v,Err(_)=>{incomplete=true;continue}};
                    if status["records"].as_array().is_none_or(Vec::is_empty) {continue;}
                    let process=match control.terminal(sid,id,"process",json!({})).await {Ok(v)=>v,Err(_)=>{incomplete=true;continue;}};
                    let title=control.terminal(sid,id,"title",json!({})).await.unwrap_or(Value::Null);
                    let records=records(&process,&status,&title,session,window,block);
                    if items.len()+records.len()>1024 {truncated=true;}
                    items.extend(records.into_iter().take(1024-items.len()));
                }
            }
        }
        let _=control.close().await;
        Ok::<_,anyhow::Error>(json!({"items":items,"partial":truncated || incomplete}))
    }).await.context("Activity refresh timed out")?
}

#[cfg(test)] mod tests {
    use super::*;
    #[test] fn live_owners_are_checked_but_completed_records_survive_exit() {
        let process=json!({"foreground":{"pid":2,"name":"fish"}});
        let status=json!({"records":[
            {"state":"working","owner":{"pid":1,"name":"codex"}},
            {"state":"blocked","owner":{"pid":2,"name":"other"}},
            {"state":"done","owner":{"pid":1,"name":"codex"},"title":"Ready","msg":"Result"},
            {"state":"error","owner":{"pid":1,"name":"codex"}},
            {"state":"future"},{"state":"working","owner":null}]});
        let items=records(&process,&status,&json!({}),&json!({"session_id":"s"}),&json!({"window_id":"w"}),&json!({"block_id":"b"}));
        assert_eq!(items.len(),3);assert_eq!(items[0]["state"],"done");assert_eq!(items[0]["app"],"codex");
        assert!(items.iter().all(|v|v["session"]=="s" && v["block"]=="b"));
    }
    #[test] fn root_titles_follow_live_osc_but_children_and_exited_owners_keep_their_titles() {
        let process=json!({"foreground":{"pid":2,"name":"codex"}});
        let status=json!({"records":[
            {"state":"working","owner":{"pid":2,"name":"codex"},"title":"Status title"},
            {"state":"blocked","id":"child","owner":{"pid":2,"name":"codex"},"title":"Child task"},
            {"state":"done","owner":{"pid":1,"name":"codex"},"title":"Previous result"}]});
        let mut items=json!(records(&process,&status,&json!({"owner":{"pid":2,"name":"codex"},"title":"⠋ Live task"}),&json!({"session_id":"s"}),&json!({}),&json!({"block_id":"b"})));
        assert_eq!(items[0]["title"],"⠋ Live task");assert_eq!(items[1]["title"],"Child task");assert_eq!(items[2]["title"],"Previous result");
        apply_title(&mut items,"s","b",&json!({"owner":{"pid":2,"name":"codex"},"title":"⠙ Changed task"}));
        assert_eq!(items[0]["title"],"⠙ Changed task");assert_eq!(items[1]["title"],"Child task");assert_eq!(items[2]["title"],"Previous result");
        apply_title(&mut items,"s","b",&json!({"owner":{"pid":99,"name":"codex"},"title":"Wrong owner"}));
        assert_eq!(items[0]["title"],"⠙ Changed task");
        apply_title(&mut items,"other","b",&json!({"owner":{"pid":2,"name":"codex"},"title":"Wrong session"}));
        assert_eq!(items[0]["title"],"⠙ Changed task");
        apply_title(&mut items,"s","b",&json!({"owner":{"pid":2,"name":"codex"},"title":""}));
        assert_eq!(items[0]["title"],"Status title");
    }

}
