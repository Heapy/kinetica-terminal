import Foundation

@main
struct LinkOracle {
    static func main() throws {
        let input = FileHandle.standardInput.readDataToEndOfFile()
        let values = try JSONSerialization.jsonObject(with: input) as! [String]
        let results = values.map { value -> [String: String] in
            let target = UntrustedURL(value)
            var result = ["value": value, "display": target.displayString]
            switch target.decision {
            case .allow(let url): result["decision"] = "allow"; result["url"] = url.absoluteString
            case .confirm(let url): result["decision"] = "confirm"; result["url"] = url.absoluteString
            case .deny(let reason): result["decision"] = "deny"; result["reason"] = String(describing: reason)
            }
            return result
        }
        FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject: results, options: [.sortedKeys]))
    }
}
