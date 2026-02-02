import re
import re
import csv
import argparse
from datetime import datetime
from collections import defaultdict
import importlib.util
import os


# --- Event Definitions ---
# Regex to capture the timestamp from any merged log line
TIMESTAMP_REGEX = re.compile(r'(\d{2}:\d{2}:\d{2}\.\d{3})')

def load_rules_from_script(script_path):
    """Dynamically loads the EVENT_RULES list from a given python script."""
    if not os.path.exists(script_path):
        print(f"ERROR: Analysis script not found at '{script_path}'")
        return None

    # Create a module spec from the file path
    spec = importlib.util.spec_from_file_location("dynamic_rules", script_path)
    if not spec or not spec.loader:
        print(f"ERROR: Could not create module spec for '{script_path}'")
        return None

    # Create a new module based on the spec
    rules_module = importlib.util.module_from_spec(spec)

    # Execute the module in its own namespace
    try:
        spec.loader.exec_module(rules_module)
    except Exception as e:
        print(f"ERROR: Failed to execute the script '{script_path}'. Error: {e}")
        return None

    # Retrieve the EVENT_RULES variable from the loaded module
    if hasattr(rules_module, 'EVENT_RULES'):
        return getattr(rules_module, 'EVENT_RULES')
    else:
        print(f"ERROR: 'EVENT_RULES' variable not found in '{script_path}'")
        return None


# Regex to capture the timestamp from any merged log line
TIMESTAMP_REGEX = re.compile(r'(\d{2}:\d{2}:\d{2}\.\d{3})')

def parse_line(line):
    """Parses a single line to extract its timestamp."""
    match = TIMESTAMP_REGEX.search(line)
    if not match:
        return None
    try:
        return datetime.strptime(match.group(1), '%H:%M:%S.%f')
    except (ValueError, IndexError):
        return None

def process_log_file(filepath):
    """
    Processes a single log file and returns a list of all completed event durations.
    """
    try:
        with open(filepath, 'r', encoding='utf-8') as f:
            lines = f.readlines()
    except FileNotFoundError:
        print(f"ERROR: The log file '{filepath}' was not found.")
        return None

    pending_events = {rule['event_name']: {} for rule in EVENT_RULES}
    completed_events = []

    for i, line in enumerate(lines):
        for rule in EVENT_RULES:
            event_name = rule['event_name']

            # Check for event end
            end_match = re.search(rule['end_regex'], line)
            if end_match:
                event_id = end_match.group(1) if rule.get('id_regex') else 'singleton'
                if event_id in pending_events[event_name]:
                    start_time, _ = pending_events[event_name].pop(event_id)
                    end_time = parse_line(line)
                    if start_time and end_time:
                        duration_ms = (end_time - start_time).total_seconds() * 1000
                        completed_events.append({'Event Name': event_name, 'Duration (ms)': duration_ms})

            # Check for event start
            start_match = re.search(rule['start_regex'], line)
            if start_match:
                event_id = start_match.group(1) if rule.get('id_regex') else 'singleton'
                start_time = parse_line(line)
                if start_time:
                    pending_events[event_name][event_id] = (start_time, i)

    return completed_events

def main():
    """
    Main function to parse arguments, process logs, and generate comparison CSV.
    """
    parser = argparse.ArgumentParser(
        description="Analyze and compare performance from one or two test log files, using their corresponding analysis scripts.",
        epilog="Example: python compare_logs.py --logs phone1.log phone2.log --scripts analyze_v1.py analyze_v2.py"
    )
    parser.add_argument('--logs', nargs='+', required=True, help='One or two merged log files to analyze.')
    parser.add_argument('--scripts', nargs='+', required=True, help='The corresponding analyze_to_csv.py script for each log file.')
    parser.add_argument('--output', default='performance_summary.csv', help='Name of the output CSV file.')

    args = parser.parse_args()

    if len(args.logs) != len(args.scripts):
        print("ERROR: You must provide exactly one --scripts file for each --logs file.")
        return
    if len(args.logs) > 2:
        print("ERROR: This script supports analyzing a maximum of two log files at a time.")
        return

    all_stats = {}
    all_rules = []

    # Use zip to pair each log file with its analysis script
    for log_file, script_file in zip(args.logs, args.scripts):
        print(f"\nLoading rules from: {script_file}...")
        event_rules = load_rules_from_script(script_file)
        if event_rules is None:
            continue

        # Add the loaded rules to our master list
        all_rules.extend(event_rules)

        print(f"Processing log file: {log_file}...")
        # Pass the specific rules for this log file to the processing function
        events = process_log_file(log_file, event_rules)
        if events is None:
            continue

        # --- Aggregation logic (remains the same) ---
        aggregated_durations = defaultdict(list)
        for event in events:
            aggregated_durations[event['Event Name']].append(event['Duration (ms)'])

        stats = {}
        for name, durations in aggregated_durations.items():
            stats[name] = {
                'count': len(durations),
                'avg_ms': sum(durations) / len(durations),
                'min_ms': min(durations),
                'max_ms': max(durations)
            }
        all_stats[log_file] = stats

    if not all_stats:
        print("No valid data was processed. Exiting.")
        return

    # --- Generate the CSV Output ---
    print(f"\nWriting summary to '{args.output}'...")

    # Get a master list of all unique event names from all combined rules
    all_event_names = sorted(list(set(rule['event_name'] for rule in all_rules)))

    header = ['Event Name']
    if len(args.logs) == 2:
        file1, file2 = args.logs
        header.extend([f'Avg_ms ({os.path.basename(file1)})', f'Avg_ms ({os.path.basename(file2)})', 'Difference (ms)', 'Performance Change (%)'])
    else: # Single file
        file1 = args.logs[0]
        header.extend([f'Avg_ms ({os.path.basename(file1)})', 'Min_ms', 'Max_ms', 'Count'])

    # --- CSV writing logic (remains the same) ---
    with open(args.output, 'w', newline='', encoding='utf-8') as f:
        writer = csv.writer(f)
        writer.writerow(header)

        for event_name in all_event_names:
            row = [event_name]
            if len(args.logs) == 2:
                file1, file2 = args.logs
                avg1 = all_stats.get(file1, {}).get(event_name, {}).get('avg_ms')
                avg2 = all_stats.get(file2, {}).get(event_name, {}).get('avg_ms')

                if avg1 is not None and avg2 is not None:
                    diff = avg2 - avg1
                    change = (diff / avg1) * 100 if avg1 != 0 else 0
                    row.extend([f"{avg1:.2f}", f"{avg2:.2f}", f"{diff:.2f}", f"{change:.2f}%"])
                else:
                    row.extend([f"{avg1:.2f}" if avg1 is not None else "N/A", f"{avg2:.2f}" if avg2 is not None else "N/A", "N/A", "N/A"])
            else:
                file1 = args.logs[0]
                stats = all_stats.get(file1, {}).get(event_name, {})
                if not stats:
                    row.extend(["N/A"] * 4)
                else:
                    row.extend([f"{stats.get('avg_ms', 0):.2f}", f"{stats.get('min_ms', 0):.2f}", f"{stats.get('max_ms', 0):.2f}", stats.get('count', 0)])
            writer.writerow(row)

    print(f"✅ Analysis complete! Summary saved to '{args.output}'.")


if __name__ == '__main__':
    main()
