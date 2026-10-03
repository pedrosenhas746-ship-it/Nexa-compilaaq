#pragma once
#include <condition_variable>
#include <functional>
#include <future>
#include <mutex>
#include <queue>
#include <thread>

namespace compatibility_layer {
// A runtime may retain TLS state on its initialization thread. Keep one worker
// for process lifetime, reusing it after shutdown instead of leaking one thread
// for every initialize cycle. Allocate this object once; never destroy it.
class InitWorker {
public:
    static InitWorker &instance() {
        static InitWorker *worker = new InitWorker();
        return *worker;
    }
    std::shared_future<bool> submit(std::function<bool()> initialize) {
        auto result = std::make_shared<std::promise<bool>>();
        auto future = result->get_future().share();
        {
            std::lock_guard<std::mutex> guard(lock_);
            tasks_.push([initialize = std::move(initialize), result] {
                try { result->set_value(initialize()); }
                catch (...) { result->set_value(false); }
            });
        }
        ready_.notify_one();
        return future;
    }
private:
    InitWorker() {
        std::thread([this] {
            for (;;) {
                std::function<void()> task;
                {
                    std::unique_lock<std::mutex> guard(lock_);
                    ready_.wait(guard, [this] { return !tasks_.empty(); });
                    task = std::move(tasks_.front());
                    tasks_.pop();
                }
                task();
            }
        }).detach();
    }
    std::mutex lock_;
    std::condition_variable ready_;
    std::queue<std::function<void()>> tasks_;
};
}
