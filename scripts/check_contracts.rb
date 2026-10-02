#!/usr/bin/env ruby
# Verify the processing contracts parse and each OpenAPI document's YAML and JSON forms are equal.
require 'json'
require 'yaml'

failures = []

contract_files = Dir['contracts/*.yaml'].sort
failures << 'no contract files found under contracts/' if contract_files.empty?
contract_files.each do |path|
  contract = YAML.safe_load(File.read(path), aliases: true)
  %w[id version].each do |key|
    failures << "#{path}: missing '#{key}'" unless contract.is_a?(Hash) && contract[key]
  end
rescue Psych::Exception => e
  failures << "#{path}: #{e.message}"
end

%w[data-processing-service-openapi data-processing-service-read-openapi].each do |name|
  yaml_path = "docs/specs/#{name}.yaml"
  json_path = "docs/specs/#{name}.json"
  begin
    from_yaml = YAML.safe_load(File.read(yaml_path), aliases: true)
    from_json = JSON.parse(File.read(json_path))
    failures << "#{name}: YAML and JSON forms differ" unless from_yaml == from_json
  rescue Psych::Exception, JSON::ParserError, SystemCallError => e
    failures << "#{name}: #{e.message}"
  end
end

if failures.empty?
  puts "Contracts valid: #{contract_files.size} processing contracts, 2 OpenAPI documents."
else
  warn failures.join("\n")
  exit 1
end
