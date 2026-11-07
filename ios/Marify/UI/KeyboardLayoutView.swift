import SwiftUI

/// The US keyboard's letters and digits, row by row, and how far each row is set in, in keys: the
/// arrangement the one-time layout is drawn on. Other arrangements are added beside it; which key
/// types which character comes from the machine's core alone.
let keyboardUS = ["1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm"]
private let keyboardUSIndent: [CGFloat] = [0, 0.5, 0.75, 1.25]

/// On the session's screen: the layout is here; it opens full screen, to be turned sideways.
struct KeyboardLayoutCard: View {
    let layout: KeyboardLayoutShown
    @State private var shown = true

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Your pepper's keyboard for this boot").font(.headline)
            Text("The machine asks for your pepper after the password. A recording of the keys is no use without this layout, and the next boot shows another.").font(.footnote)
            Button("Show the layout") { shown = true }.accessibilityIdentifier("keyboard_layout_show")
        }
        .accessibilityIdentifier("keyboard_layout_card")
        .fullScreenCover(isPresented: $shown) {
            KeyboardLayoutView(layout: layout) { shown = false }
        }
    }
}

/// The keyboard with, on every key, its own letter small and the character it types large. Best
/// seen with the phone turned sideways.
struct KeyboardLayoutView: View {
    let layout: KeyboardLayoutShown
    var onClose: () -> Void

    private func types(_ key: Character) -> String {
        guard let at = layout.keys.firstIndex(of: key) else { return " " }
        let offset = layout.keys.distance(from: layout.keys.startIndex, to: at)
        guard offset < layout.layout.count else { return " " }
        return String(layout.layout[layout.layout.index(layout.layout.startIndex, offsetBy: offset)])
    }

    var body: some View {
        GeometryReader { geo in
            let columns = CGFloat(keyboardUS.map(\.count).max() ?? 10)
            let key = (geo.size.width - 16) / columns
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Text("Type your pepper at the machine through this layout: for each character, press the key it is shown on (the small letter is the key). It holds for this boot alone. Turn the phone sideways for larger keys.")
                        .font(.footnote)
                    Spacer()
                    Button("Close", action: onClose).accessibilityIdentifier("keyboard_layout_close")
                }
                ForEach(Array(keyboardUS.enumerated()), id: \.offset) { row, letters in
                    HStack(spacing: 0) {
                        Spacer().frame(width: key * keyboardUSIndent[row])
                        ForEach(Array(letters), id: \.self) { k in
                            ZStack(alignment: .topLeading) {
                                RoundedRectangle(cornerRadius: 6).stroke(Color.secondary)
                                Text(k.uppercased()).font(.caption2).foregroundStyle(.secondary).padding(3)
                                Text(types(k))
                                    .font(.system(size: key * 0.45, weight: .bold, design: .monospaced))
                                    .foregroundStyle(Color.accentColor)
                                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                                    .accessibilityIdentifier("types_\(k)")
                            }
                            .frame(width: key - 4, height: key * 0.8)
                            .padding(2)
                        }
                    }
                }
            }
            .padding(8)
        }
        .accessibilityIdentifier("keyboard_layout")
    }
}
