import re
import csv
from datetime import datetime

# Define the path to your log file
# You can change this if your file is located elsewhere.
LOG_FILE_PATH = 'C:/Users/Azrael/AndroidStudioProjects/helloServer/tests/merged_test_run.log'

# Define regular expressions to find the key start and end points for each event.
# These are tailored to the exact format in your provided log file.

# A unique identifier for a task, which can be a UUID or a name like LONG_TASK_1
UUID_OR_LONG_TASK_REGEX = r'(?:[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}|LONG_TASK_\d+)'

EVENT_RULES = [
    {
        'event_name': '1. Full Client Round Trip',
        'description': 'Time from the client sending the request to receiving the final successful result.',
        'start_regex': r'\[CLIENT\].*\[(LONG_TASK_\d+)\] ---> Sending request...',
        'end_regex': r'\[CLIENT\].*\[(LONG_TASK_\d+)\] ✅✅✅ SUCCESS',
        'id_regex': None # Uses start/end regex for ID
    },
    {
        'event_name': '2. Network Latency & Server Acceptance',
        'description': 'Time from the client sending the request until the server logs that it is handling it.',
        'start_regex': r'\[CLIENT\].*\[(LONG_TASK_\d+)\] ---> Sending request...',
        'end_regex': r'\[SERVER\].* I TestServer: Handling: POST /recognize for task (' + UUID_OR_LONG_TASK_REGEX + r')',
        'id_regex': None,
        'is_linking_rule': True
    },

    {
        'event_name': '3. Server Permit Wait Time',
        'description': 'Time a task spends in the queue waiting for a processing permit.',
        'start_regex': r'\[SERVER\].*\[(' + UUID_OR_LONG_TASK_REGEX + r')\] - Task is now waiting for a permit',
        'end_regex': r'\[SERVER\].*\[(' + UUID_OR_LONG_TASK_REGEX + r')\] - Permit acquired, now active',
        'id_regex': None
    },
    {
        'event_name': '4. Recognizer Detection Time',
        'description': 'Core image recognition processing time, from start of work to detection completion.',
        'start_regex': r'\[SERVER\].*\[(' + UUID_OR_LONG_TASK_REGEX + r')\] - Artificial delay finished\. Starting actual work',
        'end_regex': r'\[SERVER\].*I ImageRecognizer: Detection complete',
        'id_regex': None # End is identified by thread, not ID
    },
    {
        'event_name': '5. Result Save to DB Time',
        'description': 'Time taken to write the recognition result to the internal database.',
        'start_regex': r'\[SERVER\].*I ImageRecognizer: Detection complete',
        'end_regex': r'\[SERVER\].*I ImageRecognizer: Recognition result saved to database',
        'id_regex': None # This is a global event on the same thread
    },
]

def parse_line_for_time(line):
    """Extracts a datetime object from a log line."""
    # Handle server format: [SERVER] 02-02 11:30:31.997 ...
    if line.startswith('[SERVER]'):
        match = re.search(r'\d{2}-\d{2}\s(\d{2}:\d{2}:\d{2}\.\d{3})', line)
        if match:
            return datetime.strptime(match.group(1), '%H:%M:%S.%f')
    # Handle client format: [CLIENT] [11:30:32.523] ...
    elif line.startswith('[CLIENT]'):
        match = re.search(r'\[(\d{2}:\d{2}:\d{2}\.\d{3})\]', line)
        if match:
            return datetime.strptime(match.group(1), '%H:%M:%S.%f')
    return None

def clean_log_message(line):
    """Removes timestamps, dates, thread IDs, and task IDs from a log line for clarity."""
    # Remove server date and time (e.g., "02-02 09:44:24.337")
    line = re.sub(r'\d{2}-\d{2}\s\d{2}:\d{2}:\d{2}\.\d{3}\s+', '', line)
    # Remove client time (e.g., "[09:44:24.337]")
    line = re.sub(r'\[\d{2}:\d{2}:\d{2}\.\d{3}\]\s+', '', line)
    # Remove thread and process IDs (e.g., "27600 27688 ")
    line = re.sub(r'\d+\s+\d+\s+', '', line)
    # Remove Task IDs (UUIDs or LONG_TASK_#) including brackets and hyphens
    line = re.sub(r'\[(' + UUID_OR_LONG_TASK_REGEX + r')\]\s*-?\s*', '', line)
    return line.strip()

def main():
    """Reads the log file line-by-line and writes each step and its duration to a CSV file."""
    output_csv_path = 'timeline_output.csv'  # Define the output file name

    try:
        with open(LOG_FILE_PATH, 'r', encoding='utf-8') as f:
            lines = [line.strip() for line in f if line.strip()] # Read all lines and remove empty ones
    except FileNotFoundError:
        print(f"ERROR: The log file was not found at '{LOG_FILE_PATH}'")
        return

    print(f"Processing log file and writing output to '{output_csv_path}'...")

    # Open the CSV file for writing
    with open(output_csv_path, 'w', newline='', encoding='utf-8') as csvfile:
        csv_writer = csv.writer(csvfile)

        # Write the header row
        csv_writer.writerow(['Timestamp', 'Delta_ms', 'Log_Message'])

        previous_time = None

        # This is the loop you already had, now it will work correctly
        for line in lines:
            current_time = parse_line_for_time(line)

            # Clean the log message for output
            cleaned_message = clean_log_message(line)

            # If we can't parse a time, the delta is not applicable
            if current_time is None:
                csv_writer.writerow(['N/A', 'N/A', cleaned_message])
                continue

            # For the very first line with a timestamp, delta is 0
            if previous_time is None:
                previous_time = current_time
                time_delta_ms = 0.0
            else:
                # For all subsequent lines, calculate the delta
                time_delta_ms = (current_time - previous_time).total_seconds() * 1000

            # Write the cleaned data to the CSV row
            csv_writer.writerow([
                current_time.strftime('%H:%M:%S.%f')[:-3],
                f"{time_delta_ms:.2f}",
                cleaned_message
            ])

            # Update the previous time for the next iteration
            previous_time = current_time

    print(f"✅ Granular timeline successfully saved to '{output_csv_path}'.")




if __name__ == "__main__":
    main()
