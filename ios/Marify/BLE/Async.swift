import Foundation

/// A one-shot result another thread completes: the CoreBluetooth callbacks
/// complete it, an async caller awaits it. Completing twice is a no-op, and a
/// cancelled waiter is released with `CancellationError`.
final class Gate<T: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var result: Result<T, Error>?
    private var waiter: CheckedContinuation<T, Error>?

    func complete(_ value: T) { finish(.success(value)) }
    func fail(_ error: Error) { finish(.failure(error)) }

    var isDone: Bool {
        lock.lock(); defer { lock.unlock() }
        return result != nil
    }

    private func finish(_ r: Result<T, Error>) {
        lock.lock()
        guard result == nil else { lock.unlock(); return }
        result = r
        let w = waiter
        waiter = nil
        lock.unlock()
        w?.resume(with: r)
    }

    func wait() async throws -> T {
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (c: CheckedContinuation<T, Error>) in
                lock.lock()
                if let r = result {
                    lock.unlock()
                    c.resume(with: r)
                    return
                }
                waiter = c
                lock.unlock()
            }
        } onCancel: {
            fail(CancellationError())
        }
    }
}

struct TimeoutError: Error, CustomStringConvertible {
    var description: String { "timed out" }
}

/// Runs `op` with a deadline; the losing side is cancelled.
func withTimeout<T: Sendable>(seconds: Double, _ op: @escaping @Sendable () async throws -> T) async throws -> T {
    try await withThrowingTaskGroup(of: T.self) { group in
        group.addTask { try await op() }
        group.addTask {
            try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
            throw TimeoutError()
        }
        guard let first = try await group.next() else { throw TimeoutError() }
        group.cancelAll()
        return first
    }
}

/// A monotonic clock in milliseconds, for "seen at" stamps and retry pacing.
func monotonicMs() -> Int64 { Int64(ProcessInfo.processInfo.systemUptime * 1000) }

/// Logs only in debug builds; release builds carry no diagnostics.
func debugLog(_ tag: String, _ message: @autoclosure () -> String) {
    #if DEBUG
    print("[\(tag)] \(message())")
    #endif
}
