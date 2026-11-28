/* Deterministic duplicate-DTD regression: allocation bounds, no timing test. */
#include <expat.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static size_t largest;
static void *allocate(size_t size) { if (size > largest) largest=size; return malloc(size); }
static void *resize(void *pointer,size_t size) { if (size > largest) largest=size; return realloc(pointer,size); }
static int parse(XML_Parser parser,const char *text,int final) {
    return XML_Parse(parser,text,(int)strlen(text),final)==XML_STATUS_OK;
}
static size_t duplicates(const char *declaration) {
    XML_Memory_Handling_Suite memory={allocate,resize,free};
    XML_Parser parser=XML_ParserCreate_MM(NULL,&memory,NULL);
    if (!parser) exit(2);
    largest=0;
    if (!parse(parser,"<!DOCTYPE root [",0)) exit(2);
    for (unsigned i=0;i<8192;i++) if (!parse(parser,declaration,0)) exit(2);
    if (!parse(parser,"]><root/>",1)) exit(2);
    size_t result=largest;
    XML_ParserFree(parser);
    return result;
}
static void XMLCALL element(void *user,const XML_Char *name,const XML_Char **attributes) {
    (void)name;
    int *valid=user;
    *valid=attributes[0] && attributes[1] && !attributes[2]
        && strcmp(attributes[0],"attr")==0 && strcmp(attributes[1],"first")==0;
}
int main(int argc,char **argv) {
    int negative=argc==2 && strcmp(argv[1],"--negative")==0;
    if (argc!=1 && !negative) return 2;
    size_t implied=duplicates("<!ATTLIST root attr CDATA #IMPLIED>");
    size_t required=duplicates("<!ATTLIST root attr CDATA #REQUIRED>");
    if (negative) {
        if (implied<=65536 || required<=65536) return 2;
        puts("{\"unpatchedDuplicateAttributeGrowthObserved\":true,\"duplicates\":8192,\"cases\":2}");
        return 0;
    }
    if (implied>65536 || required>65536) return 2;
    XML_Parser parser=XML_ParserCreate(NULL);
    if (!parser) return 2;
    int valid=0;
    XML_SetUserData(parser,&valid);XML_SetStartElementHandler(parser,element);
    int accepted=parse(parser,"<!DOCTYPE root [<!ATTLIST root attr CDATA 'first'><!ATTLIST root attr CDATA 'second'>]><root/>",1);
    XML_ParserFree(parser);
    if (!accepted || !valid) return 2;
    puts("{\"expatDuplicateAttributeRegression\":true,\"duplicates\":8192,\"cases\":3,\"allocationCeilingBytes\":65536,\"firstDeclarationPreserved\":true}");
    return 0;
}
