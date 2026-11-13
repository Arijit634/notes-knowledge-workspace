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
        self.assertEqual(dict(regex=[],wideScanf=[]),audit.references(' 12: 123 4 FUNC GLOBAL DEFAULT 12 regexec@@GLIBC_2.2.5'))
    def test_narrow_scanf_and_java_or_pcre_regex_are_distinct(self):
        data='\n'.join(' 12: 0 0 FUNC GLOBAL DEFAULT UND '+n for n in ['scanf','__isoc99_sscanf','fscanf','pcre2_compile_8','JavaPattern'])
        self.assertEqual(dict(regex=[],wideScanf=[]),audit.references(data))
    @unittest.skipUnless(sys.platform=='linux','Native Linux ELF metadata qualification')
    def test_real_elf_imports_detect_both_affected_families_without_execution(self):
        sources={
            'regex':'#include <regex.h>\nint main(void){regex_t p;return regcomp(&p,"fixture",0);}\n',
            'wide':'#include <wchar.h>\nint main(void){char p[128];return swscanf(L"fixture",L"%63s",p);}\n'}
        with tempfile.TemporaryDirectory() as directory:
            for name,source in sources.items():
                code=pathlib.Path(directory,name+'.c');binary=pathlib.Path(directory,name);code.write_text(source)
                subprocess.run(['gcc','-O0','-fno-builtin',str(code),'-o',str(binary)],check=True,capture_output=True)
                result=audit.references(subprocess.check_output(['readelf','--dyn-syms','--wide',str(binary)],text=True))
                self.assertTrue(result['regex' if name=='regex' else 'wideScanf'])
if __name__=='__main__':unittest.main()
