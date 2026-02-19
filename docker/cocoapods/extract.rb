#!/usr/bin/env ruby
# Extracts metadata from .podspec.json files into Annatto JSON format.
# Usage: ruby extract.rb <input_dir> <output_dir>
# Processes all *.podspec.json files in input_dir, writes *-expected.json to output_dir.
require 'json'

input_dir = ARGV[0] || '/work/in'
output_dir = ARGV[1] || '/work/out'
Dir.mkdir(output_dir) unless Dir.exist?(output_dir)

def extract_license(spec)
  lic = spec['license']
  return nil if lic.nil?
  if lic.is_a?(Hash)
    type = lic['type']
    return nil if type.nil? || type.empty?
    return type
  elsif lic.is_a?(String)
    return nil if lic.empty?
    return lic
  end
  nil
end

def extract_authors(spec)
  # Try "authors" first, then "author"
  authors = spec['authors'] || spec['author']
  return nil if authors.nil?

  if authors.is_a?(Hash)
    # {"Name" => "email"} -> join keys
    names = authors.keys.reject { |k| k.nil? || k.empty? }
    return nil if names.empty?
    return names.join(', ')
  elsif authors.is_a?(Array)
    names = authors.reject { |a| a.nil? || a.to_s.empty? }
    return nil if names.empty?
    return names.join(', ')
  elsif authors.is_a?(String)
    return nil if authors.empty?
    return authors
  end
  nil
end

def extract_description(spec)
  summary = spec['summary']
  return summary if summary && !summary.empty?
  desc = spec['description']
  return desc.strip if desc && !desc.strip.empty?
  nil
end

def extract_deps_from_map(deps_map, pod_name)
  return [] if deps_map.nil? || !deps_map.is_a?(Hash)
  result = []
  deps_map.keys.sort.each do |dep_name|
    # Skip self-referencing dependencies (subspecs of this pod)
    next if dep_name == pod_name || dep_name.start_with?("#{pod_name}/")

    constraints = deps_map[dep_name]
    vc = nil
    if constraints.is_a?(Array) && !constraints.empty?
      # Join multiple version constraints with ", "
      vc = constraints.join(', ')
    end

    result << {
      'name' => dep_name,
      'versionConstraint' => vc,
      'scope' => 'runtime'
    }
  end
  result
end

def extract_dependencies(spec)
  pod_name = spec['name'] || ''
  all_deps = {}

  # Top-level dependencies
  top_deps = spec['dependencies'] || {}
  top_deps.each { |k, v| all_deps[k] = v }

  # Subspec dependencies
  subspecs = spec['subspecs'] || []
  default_subspecs = spec['default_subspecs']

  if subspecs.is_a?(Array) && !subspecs.empty?
    if default_subspecs
      # Only include default subspecs
      defaults = default_subspecs.is_a?(Array) ? default_subspecs : [default_subspecs]
      selected = subspecs.select { |s| defaults.include?(s['name']) }
    else
      # No default_subspecs -> include all subspecs
      selected = subspecs
    end

    selected.each do |subspec|
      sub_deps = subspec['dependencies'] || {}
      sub_deps.each { |k, v| all_deps[k] = v unless all_deps.key?(k) }
    end
  end

  extract_deps_from_map(all_deps, pod_name)
end

Dir.glob(File.join(input_dir, '*.podspec.json')).sort.each do |path|
  filename = File.basename(path)
  # Strip .podspec.json to get base name
  base = filename.sub(/\.podspec\.json$/, '')

  begin
    raw = File.read(path)
    spec = JSON.parse(raw)

    result = {
      'name' => spec['name'],
      'simpleName' => spec['name'],
      'version' => spec['version'],
      'description' => extract_description(spec),
      'license' => extract_license(spec),
      'publisher' => extract_authors(spec),
      'publishedAt' => nil,
      'dependencies' => extract_dependencies(spec)
    }

    out_path = File.join(output_dir, "#{base}-expected.json")
    File.write(out_path, JSON.pretty_generate(result) + "\n")
    $stderr.puts "  [OK] #{filename} -> #{base}-expected.json"
  rescue => e
    $stderr.puts "  [ERROR] #{filename}: #{e.message}"
  end
end
