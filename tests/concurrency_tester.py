import requests
import threading
import time
import os
import json

# --- Configuration ---
SERVER_IP = "192.168.137.95"  # !!! IMPORTANT: Replace with Android device's IP address
RECOGNIZE_URL = f"http://{SERVER_IP}:8080/recognize"
SET_CONCURRENCY_URL = f"http://{SERVER_IP}:8080/set-concurrency"

# Stress test settings
START_THREADS = 1
END_THREADS = 5
STEP = 1
REQUESTS_PER_STEP = 20  # Total requests to send for each concurrency level

IMAGE_FILE = "test_image.jpg"

def get_timestamp():
    return time.strftime("%H:%M:%S", time.localtime())

def log_message(message):
    print(message)

def create_dummy_image():
    if not os.path.exists(IMAGE_FILE):
        with open(IMAGE_FILE, 'wb') as f:
            # Create a small valid-ish byte stream
            f.write(b'\x00\x01\x02\x03')

def set_server_concurrency(num_threads):
    """Sets the max_concurrent_threads on the server via the API."""
    log_message(f"[{get_timestamp()}] [SYSTEM] Setting server concurrency to {num_threads}...")
    try:
        params = {"maxThreads": num_threads}
        response = requests.post(SET_CONCURRENCY_URL, params=params, timeout=None)
        if response.ok:
            log_message(f"[{get_timestamp()}] [SYSTEM] Server updated: {response.text}")
            return True
        else:
            log_message(f"[{get_timestamp()}] [SYSTEM] Failed to update server: {response.text}")
            return False
    except Exception as e:
        log_message(f"[{get_timestamp()}] [SYSTEM] Error setting concurrency: {e}")
        return False

def send_request(req_id, results):
    """Sends a single recognition request and prints results + memory metrics."""
    start = time.time()
    try:
        with open(IMAGE_FILE, 'rb') as f:
            files = {'imageFile': (IMAGE_FILE, f, 'image/jpeg')}
            headers = {'x-client-request-id': f'stress-{req_id}'}
            response = requests.post(RECOGNIZE_URL, files=files, headers=headers, timeout=None)

        duration = time.time() - start
        if response.ok:
            results.append(duration)

            # --- PARSE JSON RESPONSE ---
            try:
                data = response.json()

                # 1. Get Recognition Results (Adjust key 'predictions' if different in your JSON)
                predictions = data.get('predictions', [])
                labels = [p.get('label', 'unknown') for p in predictions]

                # 2. Get Memory Metrics
                mem = data.get('memory_metrics', {})
                jvm_impact = mem.get('jvm_impact_mb', 'N/A')
                current_heap = mem.get('current_heap_usage_mb', 'N/A')

                log_message(
                    f"  [Req {req_id}] Results: {labels} | "
                    f"JVM Impact: {jvm_impact}MB | "
                    f"Heap: {current_heap}MB | "
                    f"Time: {duration:.2f}s"
                )
            except Exception:
                # Fallback if JSON parsing fails but request was OK
                log_message(f"  [Req {req_id}] Success (Raw): {response.text[:50]}...")

        else:
            log_message(f"  Request {req_id} FAILED: {response.status_code} - {response.text}")
    except Exception as e:
        log_message(f"  Request {req_id} ERROR: {e}")

def run_stress_test():
    create_dummy_image()

    print("="*60)
    print(f"Starting Concurrency Stress Test on {SERVER_IP}")
    print(f"Testing from {START_THREADS} to {END_THREADS} threads")
    print("="*60)

    summary = []

    for threads in range(START_THREADS, END_THREADS + 1, STEP):
        if not set_server_concurrency(threads):
            break

        time.sleep(1) # Let server settle

        log_message(f"\n[{get_timestamp()}] Testing with {threads} concurrent threads...")

        durations = []
        client_threads = []

        for i in range(threads):
            t = threading.Thread(target=send_request, args=(i, durations))
            client_threads.append(t)
            t.start()

        for t in client_threads:
            t.join()

        if durations:
            avg = sum(durations) / len(durations)
            success_rate = (len(durations) / threads) * 100
            print(f"\n  >> SUMMARY for {threads} threads: Avg: {avg:.2f}s | Success: {success_rate:.1f}%")
            summary.append((threads, avg, success_rate))
        else:
            print(f"  >> Results for {threads} threads: ALL REQUESTS FAILED")
            summary.append((threads, 0, 0))

    print("\n" + "="*60)
    print("FINAL STRESS TEST SUMMARY")
    print("Threads | Avg Latency | Success %")
    print("-" * 35)
    for s in summary:
        print(f"{s[0]:<7} | {s[1]:<11.2f}s | {s[2]:.1f}%")
    print("="*60)

if __name__ == "__main__":
    run_stress_test()