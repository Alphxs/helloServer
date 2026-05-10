import requests
import threading
import time
import os
import io
from PIL import Image

# --- Configuration ---
SERVER_IP = "172.20.10.9"
RECOGNIZE_URL = f"http://{SERVER_IP}:8080/recognize"

# Retry settings
MAX_RETRY_DURATION = 30
RETRY_INTERVAL = 2

# Discovery settings
START_THREADS = 1
MAX_THREADS = 2          # Upper bound for thread discovery
THREAD_STEP = 1           # Increase by 1 thread each round
REQUESTS_PER_LEVEL = 5    # Requests per thread level to average out noise
FAILURE_THRESHOLD = 0.5   # Stop if more than 50% of requests fail

# Image size discovery settings
START_IMAGE_KB = 10000
MAX_IMAGE_KB = 5000000
IMAGE_STEP_KB = 20000

IMAGE_FILE = "test_image_1.jpg"

def get_timestamp():
    return time.strftime("%H:%M:%S", time.localtime())

def log_message(message):
    print(message)

def create_image_of_size(size_kb):
    """Creates a real JPEG image of approximately size_kb kilobytes."""
    # Scale dimensions to approximate the target size
    # A rough estimate: width * height * 3 bytes (RGB) compressed ~10:1 by JPEG
    target_bytes = size_kb * 1024
    side = max(64, int((target_bytes / 3) ** 0.5))
    
    img = Image.new('RGB', (side, side), color=(100, 149, 237))
    buffer = io.BytesIO()
    img.save(buffer, format='JPEG', quality=85)
    return buffer.getvalue()

def send_request(req_id, results, image_data):
    """Sends a recognition request with retry logic."""
    attempt = 0
    retry_start = time.time()

    while True:
        attempt += 1
        start = time.time()

        try:
            files = {'imageFile': ('test.jpg', io.BytesIO(image_data), 'image/jpeg')}
            headers = {'x-client-request-id': f'stress-{req_id}'}
            response = requests.post(RECOGNIZE_URL, files=files, headers=headers, timeout=30)

            duration = time.time() - start

            if response.ok:
                total_duration = time.time() - retry_start
                results.append(('success', duration, total_duration, attempt))
                log_message(f"  [Req {req_id}] Attempt {attempt} SUCCESS | Time: {duration:.2f}s")
                return

            else:
                log_message(f"  [Req {req_id}] Attempt {attempt} FAILED: {response.status_code} - {response.text} — retrying in {RETRY_INTERVAL}s...")

        except requests.exceptions.ConnectionError:
            elapsed = time.time() - retry_start
            log_message(f"  [Req {req_id}] Attempt {attempt} CONNECTION ERROR | Elapsed: {elapsed:.1f}s — retrying in {RETRY_INTERVAL}s...")

        except requests.exceptions.Timeout:
            elapsed = time.time() - retry_start
            log_message(f"  [Req {req_id}] Attempt {attempt} TIMEOUT | Elapsed: {elapsed:.1f}s — retrying in {RETRY_INTERVAL}s...")

        except Exception as e:
            elapsed = time.time() - retry_start
            log_message(f"  [Req {req_id}] Attempt {attempt} ERROR: {e} | Elapsed: {elapsed:.1f}s — retrying in {RETRY_INTERVAL}s...")

        if time.time() - retry_start >= MAX_RETRY_DURATION:
            results.append(('failed', 0, MAX_RETRY_DURATION, attempt))
            log_message(f"  [Req {req_id}] GAVE UP after {attempt} attempts.")
            return

        time.sleep(RETRY_INTERVAL)

def run_level(num_threads, image_data):
    """Runs one level of the stress test and returns (avg_duration, success_rate)."""
    results = []
    threads = []

    for i in range(num_threads * REQUESTS_PER_LEVEL):
        t = threading.Thread(target=send_request, args=(i, results, image_data))
        threads.append(t)

    # Launch threads in batches of num_threads
    for i in range(0, len(threads), num_threads):
        batch = threads[i:i + num_threads]
        for t in batch:
            t.start()
        for t in batch:
            t.join()

    successes = [r for r in results if r[0] == 'success']
    success_rate = len(successes) / len(results) if results else 0
    avg_duration = sum(r[1] for r in successes) / len(successes) if successes else 0

    return avg_duration, success_rate

def discover_thread_limit(image_data):
    """Gradually increases threads until the server starts failing."""
    print("\n" + "="*60)
    print("PHASE 1: Discovering thread limit")
    print("="*60)

    summary = []
    last_stable_threads = START_THREADS

    for threads in range(START_THREADS, MAX_THREADS + 1, THREAD_STEP):
        log_message(f"\n[{get_timestamp()}] Testing {threads} concurrent thread(s)...")
        avg, success_rate = run_level(threads, image_data)

        status = "OK" if success_rate >= (1 - FAILURE_THRESHOLD) else "DEGRADED"
        log_message(f"  >> {threads} threads | Avg: {avg:.2f}s | Success: {success_rate*100:.1f}% | {status}")
        summary.append((threads, avg, success_rate))

        if success_rate >= (1 - FAILURE_THRESHOLD):
            last_stable_threads = threads
        else:
            log_message(f"\n  !! Server degraded at {threads} threads. Last stable: {last_stable_threads}")
            break

    return last_stable_threads, summary

def discover_image_size_limit(stable_threads):
    """Gradually increases image size until the server starts failing."""
    print("\n" + "="*60)
    print(f"PHASE 2: Discovering image size limit at {stable_threads} thread(s)")
    print("="*60)

    summary = []
    last_stable_size = START_IMAGE_KB

    for size_kb in range(START_IMAGE_KB, MAX_IMAGE_KB + 1, IMAGE_STEP_KB):
        log_message(f"\n[{get_timestamp()}] Testing image size: {size_kb}KB...")
        image_data = create_image_of_size(size_kb)
        avg, success_rate = run_level(stable_threads, image_data)

        status = "OK" if success_rate >= (1 - FAILURE_THRESHOLD) else "DEGRADED"
        log_message(f"  >> {size_kb}KB | Avg: {avg:.2f}s | Success: {success_rate*100:.1f}% | {status}")
        summary.append((size_kb, avg, success_rate))

        if success_rate >= (1 - FAILURE_THRESHOLD):
            last_stable_size = size_kb
        else:
            log_message(f"\n  !! Server degraded at {size_kb}KB. Last stable: {last_stable_size}KB")
            break

    return last_stable_size, summary

def run_stress_test():
    print("="*60)
    print(f"Auto-Discovery Stress Test on {SERVER_IP}")
    print("="*60)

    # Use a small baseline image for thread discovery
    baseline_image = create_image_of_size(START_IMAGE_KB)

    # Phase 1: find thread limit
    stable_threads, thread_summary = discover_thread_limit(baseline_image)

    # Phase 2: find image size limit using stable thread count
    stable_size, size_summary = discover_image_size_limit(stable_threads)

    # Final report
    print("\n" + "="*60)
    print("FINAL REPORT")
    print("="*60)
    print(f"Max stable threads : {stable_threads}")
    print(f"Max stable image   : {stable_size}KB")

    print("\nThread Discovery:")
    print(f"{'Threads':<10} | {'Avg Latency':<13} | {'Success %'}")
    print("-" * 40)
    for t, avg, sr in thread_summary:
        print(f"{t:<10} | {avg:<13.2f}s | {sr*100:.1f}%")

    print("\nImage Size Discovery:")
    print(f"{'Size (KB)':<10} | {'Avg Latency':<13} | {'Success %'}")
    print("-" * 40)
    for s, avg, sr in size_summary:
        print(f"{s:<10} | {avg:<13.2f}s | {sr*100:.1f}%")
    print("="*60)

if __name__ == "__main__":
    run_stress_test()