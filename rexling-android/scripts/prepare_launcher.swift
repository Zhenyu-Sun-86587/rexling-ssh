import AppKit
precondition(CommandLine.arguments.count==3,"Usage: swift prepare_launcher.swift SOURCE.png OUTPUT.png")
let source=URL(fileURLWithPath:CommandLine.arguments[1])
let output=URL(fileURLWithPath:CommandLine.arguments[2])
let data=try Data(contentsOf:source)
let image=NSBitmapImageRep(data:data)!.cgImage!
// Asset framing only: discard the supplied black outer mask, retaining exact artwork.
let inset=Int((Double(min(image.width,image.height))*0.10).rounded())
let rect=CGRect(x:inset,y:inset,width:image.width-2*inset,height:image.height-2*inset)
let cropped=image.cropping(to:rect)!
let bitmap=NSBitmapImageRep(cgImage:cropped)
for (x,y) in [(0,0),(cropped.width-1,0),(0,cropped.height-1),(cropped.width-1,cropped.height-1)] {
    let color=bitmap.colorAt(x:x,y:y)!.usingColorSpace(.deviceRGB)!
    precondition(color.blueComponent>0.3 && color.alphaComponent>0.99,"Crop must have no black or transparent corners")
}
try bitmap.representation(using:.png,properties:[:])!.write(to:output)
print("Prepared \(cropped.width)x\(cropped.height) opaque launcher artwork; crop=\(inset)px on every side")
