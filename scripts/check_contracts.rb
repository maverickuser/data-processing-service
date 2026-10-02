#!/usr/bin/env ruby
# Verify the processing contracts and OpenAPI documents are present and well formed:
# - exactly the four expected contracts exist, parse, and carry their identifying keys;
# - each mapping contract names an existing source contract;
# - each OpenAPI document has the same content in its YAML and JSON forms and the core sections.
require 'json'
require 'yaml'

ROOT = ARGV[0] || '.'

SOURCE_CONTRACTS = %w[bse-debt-bhavcopy-csv-v1 nsdl-security-json-v1].freeze
MAPPING_CONTRACTS = %w[bse-debt-bhavcopy-mapping-v1 nsdl-security-mapping-v1].freeze
OPENAPI_DOCUMENTS = %w[data-processing-service-openapi data-processing-service-read-openapi].freeze

def load_yaml(path)
  YAML.safe_load(File.read(path), aliases: true)
end

def contract_failures(root)
  failures = []
  expected = (SOURCE_CONTRACTS + MAPPING_CONTRACTS).sort
  found = Dir[File.join(root, 'contracts', '*.yaml')].map { |path| File.basename(path, '.yaml') }.sort
  failures << "contracts/: expected #{expected.join(', ')} but found #{found.join(', ')}" unless found == expected

  (found & expected).each do |name|
    path = File.join(root, 'contracts', "#{name}.yaml")
    contract = load_yaml(path)
    unless contract.is_a?(Hash)
      failures << "#{path}: not a mapping"
      next
    end
    required = SOURCE_CONTRACTS.include?(name) ? %w[id version dataset format] : %w[id version sourceContract]
    missing = required.reject { |key| contract[key] }
    failures << "#{path}: missing #{missing.join(', ')}" unless missing.empty?
    failures << "#{path}: id and version do not match the file name" unless "#{contract['id']}-#{contract['version']}" == name
    source = contract['sourceContract']
    failures << "#{path}: unknown sourceContract #{source}" if source && !SOURCE_CONTRACTS.include?(source)
  rescue Psych::Exception => e
    failures << "#{path}: #{e.message}"
  end
  failures
end

def openapi_failures(root)
  OPENAPI_DOCUMENTS.flat_map do |name|
    yaml_path = File.join(root, 'docs', 'specs', "#{name}.yaml")
    json_path = File.join(root, 'docs', 'specs', "#{name}.json")
    from_yaml = load_yaml(yaml_path)
    from_json = JSON.parse(File.read(json_path))
    failures = []
    failures << "#{name}: YAML and JSON forms differ" unless from_yaml == from_json
    missing = %w[openapi info paths components].reject { |key| from_yaml.is_a?(Hash) && from_yaml[key] }
    failures << "#{name}: missing #{missing.join(', ')}" unless missing.empty?
    failures << "#{name}: no paths defined" if from_yaml.is_a?(Hash) && from_yaml['paths'].is_a?(Hash) && from_yaml['paths'].empty?
    failures
  rescue Psych::Exception, JSON::ParserError, SystemCallError => e
    ["#{name}: #{e.message}"]
  end
end

failures = contract_failures(ROOT) + openapi_failures(ROOT)
if failures.empty?
  puts "Contracts valid: #{SOURCE_CONTRACTS.size + MAPPING_CONTRACTS.size} processing contracts, #{OPENAPI_DOCUMENTS.size} OpenAPI documents."
else
  warn failures.join("\n")
  exit 1
end
