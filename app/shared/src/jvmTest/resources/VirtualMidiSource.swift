// A virtual MIDI keyboard for DesktopMidiHotplugTest: it "plugs in" a CoreMIDI source,
// presses middle C after the given number of seconds and "unplugs" again.
import CoreMIDI
import Foundation

let name = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "MBC Hotplug Test"
let pressAfter = CommandLine.arguments.count > 2 ? Double(CommandLine.arguments[2]) ?? 3 : 3

var client = MIDIClientRef()
MIDIClientCreate("MusicBootCamp test" as CFString, nil, nil, &client)
var source = MIDIEndpointRef()
MIDISourceCreate(client, name as CFString, &source)
print("plugged"); fflush(stdout)

Thread.sleep(forTimeInterval: pressAfter)
var packets = MIDIPacketList()
var packet = MIDIPacketListInit(&packets)
let noteOn: [UInt8] = [0x90, 60, 100]
packet = MIDIPacketListAdd(&packets, MemoryLayout<MIDIPacketList>.size, packet, 0, noteOn.count, noteOn)
MIDIReceived(source, &packets)
print("pressed"); fflush(stdout)

Thread.sleep(forTimeInterval: 1.5)
MIDIEndpointDispose(source)
print("unplugged"); fflush(stdout)
Thread.sleep(forTimeInterval: 0.5)
