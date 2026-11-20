"""FAST symbol parser regressions. No Docker, copied code or provider calls."""
import importlib.util,pathlib,subprocess,sys,tempfile,unittest
spec=importlib.util.spec_from_file_location('audit',pathlib.Path(__file__).with_name('runtime-symbol-audit.py'))
audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)
class RuntimeSymbolImports(unittest.TestCase):
    def test_versioned_regex_and_gnu_entry_points_are_detected(self):
        data=' 12: 0 0 FUNC GLOBAL DEFAULT UND regexec@GLIBC_2.2.5 (2)\n 13: 0 0 FUNC GLOBAL DEFAULT UND re_compile_pattern@GLIBC_2.2.5 (2)'
        self.assertEqual(['re_compile_pattern','regexec'],audit.references(data)['regex'])
    def test_wide_iso_and_variadic_scanf_are_detected(self):
        names=['swscanf','__isoc99_vswscanf','__isoc23_fwscanf','vwscanf','__swscanf_chk']
        data='\n'.join(' 12: 0 0 FUNC GLOBAL DEFAULT UND '+n+'@GLIBC_2.2.5 (2)' for n in names)
        self.assertEqual(sorted(names),audit.references(data)['wideScanf'])
    def test_definitions_are_not_mistaken_for_call_sites(self):
        self.assertEqual(dict(regex=[],wideScanf=[],cxxFileStreams=[],glob=[]),audit.references(' 12: 123 4 FUNC GLOBAL DEFAULT 12 regexec@@GLIBC_2.2.5'))
    def test_narrow_scanf_and_java_or_pcre_regex_are_distinct(self):
        data='\n'.join(' 12: 0 0 FUNC GLOBAL DEFAULT UND '+n for n in ['scanf','__isoc99_sscanf','fscanf','pcre2_compile_8','JavaPattern'])
        self.assertEqual(dict(regex=[],wideScanf=[],cxxFileStreams=[],glob=[]),audit.references(data))
    def test_glob_imports_are_distinct_from_definitions_and_fnmatch(self):
        names=['glob','glob64','globfree','globfree64']
        data='\n'.join(' 12: 0 0 FUNC GLOBAL DEFAULT UND '+n+'@GLIBC_2.2.5 (2)' for n in names)
        self.assertEqual(names,audit.references(data)['glob'])
        self.assertEqual([],audit.references(' 12: 123 4 FUNC GLOBAL DEFAULT 12 glob@@GLIBC_2.2.5')['glob'])
        self.assertEqual([],audit.references(' 12: 0 0 FUNC GLOBAL DEFAULT UND fnmatch@GLIBC_2.2.5')['glob'])
    def test_file_stream_abi_names_and_stdio_desynchronization_are_detected(self):
        names=['_ZNSt13basic_filebufIcSt11char_traitsIcEEC1Ev',
            '_ZTVNSt7__cxx1113basic_filebufIwSt11char_traitsIwEEE',
            '_ZNSt14basic_ofstreamIcSt11char_traitsIcEED1Ev',
            '_ZNSt8ios_base15sync_with_stdioEb']
        data='\n'.join(' 12: 0 0 FUNC GLOBAL DEFAULT UND '+name+'@GLIBCXX_3.4 (2)' for name in names)
        self.assertEqual(sorted(names),audit.references(data)['cxxFileStreams'])
        definition=' 12: 123 4 FUNC GLOBAL DEFAULT 12 '+names[0]
        self.assertEqual([],audit.references(definition)['cxxFileStreams'])
    @unittest.skipUnless(sys.platform=='linux','Native Linux ELF metadata qualification')
    def test_real_cpp_file_stream_and_desynchronization_imports_without_execution(self):
        sources={
            'file':'#include <fstream>\nint main(){std::ofstream f("unused-synthetic-path");f.put(\'x\');}\n',
            'stdio':'#include <iostream>\nint main(){std::ios_base::sync_with_stdio(false);std::cout.put(\'x\');}\n'}
        with tempfile.TemporaryDirectory() as directory:
            for name,source in sources.items():
                code=pathlib.Path(directory,name+'.cc');binary=pathlib.Path(directory,name);code.write_text(source)
                subprocess.run(['g++','-O0',str(code),'-o',str(binary)],check=True,capture_output=True)
                result=audit.references(subprocess.check_output(['readelf','--dyn-syms','--wide',str(binary)],text=True))
                self.assertTrue(result['cxxFileStreams'])
    @unittest.skipUnless(sys.platform=='linux','Native Linux ELF metadata qualification')
    def test_real_elf_imports_detect_both_affected_families_without_execution(self):
        sources={
            'regex':'#include <regex.h>\nint main(void){regex_t p;return regcomp(&p,"fixture",0);}\n',
            'wide':'#include <wchar.h>\nint main(void){char p[128];return swscanf(L"fixture",L"%63s",p);}\n',
            'glob':'#include <glob.h>\nint main(void){glob_t p;return glob("unused-synthetic-path",0,0,&p);}\n'}
        with tempfile.TemporaryDirectory() as directory:
            for name,source in sources.items():
                code=pathlib.Path(directory,name+'.c');binary=pathlib.Path(directory,name);code.write_text(source)
                subprocess.run(['gcc','-O0','-fno-builtin',str(code),'-o',str(binary)],check=True,capture_output=True)
                result=audit.references(subprocess.check_output(['readelf','--dyn-syms','--wide',str(binary)],text=True))
                self.assertTrue(result[{'regex':'regex','wide':'wideScanf','glob':'glob'}[name]])
if __name__=='__main__':unittest.main()
