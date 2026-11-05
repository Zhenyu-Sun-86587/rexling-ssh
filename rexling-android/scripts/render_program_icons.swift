// Convert the supplied self-contained SVG tiles with WebKit's SVG/CSS renderer.
// This renders local assets only; it does not control or read a user's browser.
import AppKit
import WebKit
let args=CommandLine.arguments
precondition(args.count==3,"Usage: swift render_program_icons.swift SOURCE_DIR OUTPUT_DIR")
let source=URL(fileURLWithPath:args[1]);let output=URL(fileURLWithPath:args[2])
try FileManager.default.createDirectory(at:output,withIntermediateDirectories:true)
let files=try FileManager.default.contentsOfDirectory(at:source,includingPropertiesForKeys:nil).filter {$0.pathExtension=="svg"}.sorted {$0.lastPathComponent<$1.lastPathComponent}
let app=NSApplication.shared;app.setActivationPolicy(.prohibited)
final class Renderer:NSObject,WKNavigationDelegate {
 let web=WKWebView(frame:NSRect(x:0,y:0,width:244,height:192))
 let window=NSWindow(contentRect:NSRect(x:0,y:0,width:244,height:192),styleMask:.borderless,backing:.buffered,defer:false)
 var index=0
 override init() {super.init();web.navigationDelegate=self;web.setValue(false,forKey:"drawsBackground");window.contentView=web}
 func next() {
  if index==files.count {print("Rendered \(index) SVG tiles");exit(0)}
  do {
   var svg=try String(contentsOf:files[index],encoding:.utf8)
   svg=svg.replacingOccurrences(of:"<\\?xml[^>]*>",with:"",options:.regularExpression)
   // The source has transparent margins; crop those, preserving the tile's geometry.
   if let range=svg.range(of:"viewBox=\"0 0 73 60\"") {svg.replaceSubrange(range,with:"viewBox=\"6 6 61 48\"")}
   web.loadHTMLString("<html><head><style>html,body{margin:0;width:244px;height:192px;background:transparent;overflow:hidden}body>svg{width:244px;height:192px}</style></head><body>"+svg+"</body></html>",baseURL:source)
  } catch {fputs("\(error)\n",stderr);exit(1)}
 }
 func webView(_ webView:WKWebView,didFinish navigation:WKNavigation!) {
  DispatchQueue.main.asyncAfter(deadline:.now()+0.1) {
   let config=WKSnapshotConfiguration();config.rect=NSRect(x:0,y:0,width:244,height:192)
   self.web.takeSnapshot(with:config) {image,error in
    guard let image=image,let data=image.tiffRepresentation,let bitmap=NSBitmapImageRep(data:data),let png=bitmap.representation(using:.png,properties:[:]) else {fputs("SVG snapshot failed: \(String(describing:error))\n",stderr);exit(1)}
    do {try png.write(to:output.appendingPathComponent("catalog_"+files[self.index].deletingPathExtension().lastPathComponent.replacingOccurrences(of:"-",with:"_")+".png"))} catch {fputs("\(error)\n",stderr);exit(1)}
    self.index+=1;self.next()
   }
  }
 }
}
let renderer=Renderer();renderer.next();app.run()
