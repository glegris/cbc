require 'pathname'

def main
  Dir.chdir Pathname.new($PROGRAM_NAME).realpath.dirname.dirname + 'test'
  test_sh = File.read('test_cbc.sh')
  tested = test_sh.scan(/[\$\w\-]+\.c/).reject {|n| /\$/ =~ n } +
           test_sh.scan(%r<\./[\w\-]+>).map {|n| File.basename(n) + '.c' }
  tested = tested.uniq
  print_list Dir.glob('*.c') - tested
end

def print_list(list)
  list.sort.each do |name|
    puts name
  end
end

main
