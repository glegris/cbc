require 'pathname'

def main
  Dir.chdir Pathname.new($PROGRAM_NAME).realpath.dirname.dirname + 'test'
  tested = (tested_from_x86_suite + tested_from_jvm_suite).uniq
  print_list Dir.glob('*.c') - tested
end

# test_cbc.sh (the native x86 suite, run via run.sh) references each
# test either as a bare "name.c" or as "./name" (the compiled binary).
def tested_from_x86_suite
  src = File.read('test_cbc.sh')
  src.scan(/[\$\w\-]+\.c/).reject { |n| /\$/ =~ n } +
    src.scan(%r{\./[\w\-]+}).map { |n| File.basename(n) + '.c' }
end

# run_jvm.sh (the JVM backend suite) references each test by its bare
# NAME, the first argument to one of a handful of helper functions --
# run_case/run_java_api_case/assert_error_contains take "NAME.c"
# directly, run_multifile_case/assert_multifile_error_contains take
# "NAME-*.c" (several files sharing one prefix; see either helper's own
# doc comment in run_jvm.sh for the convention).
def tested_from_jvm_suite
  src = File.read('run_jvm.sh')
  single = src.scan(/^(?:run_case|run_java_api_case|assert_error_contains)\s+([\w-]+)/).flatten
  multi = src.scan(/^(?:run_multifile_case|assert_multifile_error_contains)\s+([\w-]+)/).flatten
  single.map { |n| n + '.c' } +
    multi.flat_map { |n| Dir.glob("#{n}-*.c") }
end

def print_list(list)
  list.sort.each do |name|
    puts name
  end
end

main
