import requests
import threading
import time
import os
import json

# --- Configuration ---
SERVER_IP = "172.20.10.9"  # !!! IMPORTANT: Replace with Android device's IP address
RECOGNIZE_URL = f"http://{SERVER_IP}:8080/recognize"
SET_CONCURRENCY_URL = f"http://{SERVER_IP}:8080/set-concurrency"

# Stress test settings
START_THREADS = 1
END_THREADS = 10
STEP = 1
REQUESTS_PER_STEP = 20  # Total requests to send for each concurrency level

IMAGE_FILE = "test_image_1.jpg"

def get_timestamp():
    return time.strftime("%H:%M:%S", time.localtime())

def log_message(message):
    print(message)

def create_dummy_image():
    if not os.path.exists(IMAGE_FILE):
        with open(IMAGE_FILE, 'wb') as f:
            f.write(b'\x00\x01\x02\x03')

def set_server_concurrency(num_threads):
    """Sets the max_concurrent_threads on the server via the API."""
    log_message(f"[{get_timestamp()}] [SYSTEM] Setting server concurrency to {num_threads}...")
    try:
        params = {"maxThreads": num_threads}
        response = requests.post(SET_CONCURRENCY_URL, params=params, timeout=10)
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
    """Sends a single recognition request and records the duration."""
    start = time.time()
    try:
        with open(IMAGE_FILE, 'rb') as f:
            files = {'imageFile': (IMAGE_FILE, f, 'image/jpeg')}
            headers = {'x-client-request-id': f'stress-{req_id}'}
            response = requests.post(RECOGNIZE_URL, files=files, headers=headers, timeout=180)
        
        duration = time.time() - start
        if response.ok:
            results.append(duration)
        else:
            log_message(f"  Request {req_id} FAILED: {response.status_code}")
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
        
        log_message(f"\n[{get_timestamp()}] Testing with {threads} concurrent threads ({REQUESTS_PER_STEP} requests total)...")
        
        durations = []
        client_threads = []
        
        # We send REQUESTS_PER_STEP requests simultaneously. 
        # Since the server is set to 'threads' concurrency, it will process 'threads' at a time.
        for i in range(REQUESTS_PER_STEP):
            t = threading.Thread(target=send_request, args=(i, durations))
            client_threads.append(t)
            t.start()
            time.sleep(0.01) # Stagger slightly
            
        for t in client_threads:
            t.join()
            
        if durations:
            avg = sum(durations) / len(durations)
            success_rate = (len(durations) / REQUESTS_PER_STEP) * 100
            print(f"  >> Results for {threads} threads: Avg Duration: {avg:.2f}s | Success Rate: {success_rate:.1f}%")
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
