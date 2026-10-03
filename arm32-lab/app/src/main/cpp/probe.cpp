// SPDX-License-Identifier: GPL-2.0-only
// CPU/ELF experiment. This is not an Android runtime or a game launcher.
#include <unicorn/unicorn.h>
#include <unicorn/arm.h>
#include <elf.h>
#include <algorithm>
#include <cstring>
#include <fstream>
#include <map>
#include <set>
#include <sstream>
#include <stdexcept>
#include <vector>
#ifdef __ANDROID__
#include <jni.h>
#endif

namespace {
constexpr uint32_t BASE=0x10000000, STACK=0x20000000, TRAPS=0x30000000, VM=0x40000000, END=0x50000000;
void check(uc_err e){if(e!=UC_ERR_OK)throw std::runtime_error(uc_strerror(e));}
struct Cpu {
    uc_engine* uc=nullptr;
    std::map<uint32_t,std::string> traps;
    std::string stopped;
    uint64_t invalid=0;
    Cpu(){
        check(uc_open(UC_ARCH_ARM,UC_MODE_ARM,&uc));
        try{
            check(uc_ctl_set_cpu_model(uc,UC_CPU_ARM_CORTEX_A15));
            uint32_t cpacr=0,fpexc=0x40000000;
            check(uc_reg_read(uc,UC_ARM_REG_C1_C0_2,&cpacr));cpacr|=0xf<<20;
            check(uc_reg_write(uc,UC_ARM_REG_C1_C0_2,&cpacr));
            check(uc_reg_write(uc,UC_ARM_REG_FPEXC,&fpexc));
        }catch(...){uc_close(uc);uc=nullptr;throw;}
    }
    ~Cpu(){if(uc)uc_close(uc);}
    Cpu(const Cpu&)=delete;
    static void code(uc_engine* u,uint64_t addr,uint32_t,void* opaque){
        auto& self=*static_cast<Cpu*>(opaque);auto it=self.traps.find(uint32_t(addr));
        if(it!=self.traps.end()){self.stopped=it->second;uc_emu_stop(u);}
    }
    static bool fault(uc_engine*,uc_mem_type,uint64_t addr,int,int64_t,void* opaque){
        static_cast<Cpu*>(opaque)->invalid=addr;return false;
    }
    void write(uint32_t addr,const void* bytes,size_t size){check(uc_mem_write(uc,addr,bytes,size));}
    uint32_t word(uint32_t addr){uint32_t v;check(uc_mem_read(uc,addr,&v,4));return v;}
    void put(uint32_t addr,uint32_t v){write(addr,&v,4);}
    void setup(){
        check(uc_mem_map(uc,STACK,0x100000,UC_PROT_READ|UC_PROT_WRITE));
        check(uc_mem_map(uc,TRAPS,0x10000,UC_PROT_ALL));
        check(uc_mem_map(uc,VM,0x1000,UC_PROT_READ|UC_PROT_WRITE));
        check(uc_mem_map(uc,END,0x1000,UC_PROT_ALL));
        uint32_t sp=STACK+0xffff0;check(uc_reg_write(uc,UC_ARM_REG_SP,&sp));
        check(uc_reg_write(uc,UC_ARM_REG_LR,&END));
        uc_hook hook;check(uc_hook_add(uc,&hook,UC_HOOK_CODE,(void*)code,this,TRAPS,TRAPS+0xffff));
        check(uc_hook_add(uc,&hook,UC_HOOK_MEM_INVALID,(void*)fault,this,1,0));
    }
    uint32_t trap(const std::string& name){
        if(traps.size()>=16000)throw std::runtime_error("Excesso de imports");
        uint32_t p=TRAPS+uint32_t(traps.size()*4);traps[p]=name;put(p,0xe12fff1e);return p;
    }
    uint32_t call(uint32_t address,uint32_t r0=0,uint32_t r1=0){
        stopped.clear();invalid=0;
        check(uc_reg_write(uc,UC_ARM_REG_R0,&r0));check(uc_reg_write(uc,UC_ARM_REG_R1,&r1));
        uint32_t sp=STACK+0xffff0,lr=END;check(uc_reg_write(uc,UC_ARM_REG_SP,&sp));check(uc_reg_write(uc,UC_ARM_REG_LR,&lr));
        uc_err error=uc_emu_start(uc,address,END,1000000,100000);
        if(!stopped.empty())throw std::runtime_error("Ponte pendente: "+stopped);
        if(error!=UC_ERR_OK){std::ostringstream out;out<<uc_strerror(error)<<"; endereço 0x"<<std::hex<<invalid;throw std::runtime_error(out.str());}
        uint32_t pc=0;check(uc_reg_read(uc,UC_ARM_REG_PC,&pc));
        if(pc!=END)throw std::runtime_error("Limite de execução atingido (100 mil instruções / 1 segundo)");
        check(uc_reg_read(uc,UC_ARM_REG_R0,&r0));return r0;
    }
};

std::string selfTest(){
    Cpu cpu;cpu.setup();check(uc_mem_map(cpu.uc,BASE,0x1000,UC_PROT_ALL));
    // Actual A32 function: mov r0,#40; add r0,r0,#2; bx lr.
    const uint32_t arm[]={0xe3a00028,0xe2800002,0xe12fff1e};cpu.write(BASE,arm,sizeof arm);
    if(cpu.call(BASE)!=42)throw std::runtime_error("ARM: resultado incorreto");
    // Actual Thumb function: movs r0,#7; adds r0,#5; bx lr.
    const uint16_t thumb[]={0x2007,0x3005,0x4770};cpu.write(BASE+0x100,thumb,sizeof thumb);
    if(cpu.call(BASE+0x101)!=12)throw std::runtime_error("Thumb: resultado incorreto");
    // A32 load/add/store through a guest pointer, never a host pointer.
    const uint32_t mem[]={0xe5901000,0xe2811009,0xe5801000,0xe1a00001,0xe12fff1e};cpu.write(BASE+0x200,mem,sizeof mem);
    cpu.put(VM+0x800,33);
    if(cpu.call(BASE+0x200,VM+0x800)!=42||cpu.word(VM+0x800)!=42)throw std::runtime_error("Memória ARM: resultado incorreto");
    // ARMv7 NEON: copy an eight-byte vector through guest memory.
    const uint32_t neon[]={0xf421070d,0xf40c070d,0xe12fff1e};cpu.write(BASE+0x400,neon,sizeof neon);
    uint32_t src=VM+0x900,dst=VM+0x920;uint64_t value=0x8877665544332211,got=0;
    cpu.write(src,&value,8);check(uc_reg_write(cpu.uc,UC_ARM_REG_R12,&dst));cpu.call(BASE+0x400,0,src);
    check(uc_mem_read(cpu.uc,dst,&got,8));if(got!=value)throw std::runtime_error("NEON: cópia incorreta");
    const uint32_t loop=0xeafffffe;cpu.put(BASE+0x300,loop);
    bool limited=false;try{cpu.call(BASE+0x300);}catch(const std::exception& e){limited=std::string(e.what()).find("Limite")!=std::string::npos;}
    if(!limited)throw std::runtime_error("Limite de instruções falhou");
    return "ARM32 executado pelo motor de 64 bits: ARM=42; Thumb=12; memória=42; loop interrompido; NEON=OK.\n";
}

struct Elf {
    Cpu cpu;
    std::vector<unsigned char> bytes;
    std::map<int32_t,uint32_t> tags;
    std::vector<Elf32_Sym> symbols;
    std::map<std::string,uint32_t> exports;
    std::set<std::string> imports;
    std::vector<std::string> needed;
    unsigned relocations=0;
    template<class T>T at(size_t offset)const{
        if(offset>bytes.size()||sizeof(T)>bytes.size()-offset)throw std::runtime_error("ELF truncado");
        T result;std::memcpy(&result,bytes.data()+offset,sizeof result);return result;
    }
    uint32_t address(uint32_t relative)const{
        if(relative>=0x08000000)throw std::runtime_error("Endereço ELF excessivo");
        return BASE+relative;
    }
    std::string string(uint32_t index){
        if(!tags.count(DT_STRTAB)||!tags.count(DT_STRSZ)||index>=tags.at(DT_STRSZ))throw std::runtime_error("String ELF inválida");
        std::string result;uint32_t pos=address(tags.at(DT_STRTAB)+index);
        for(uint32_t i=index;i<tags.at(DT_STRSZ)&&result.size()<1024;i++,pos++){
            char c;check(uc_mem_read(cpu.uc,pos,&c,1));if(!c)return result;result+=c;
        }throw std::runtime_error("String ELF sem terminador");
    }
    void relocate(uint32_t offset,uint32_t size){
        if(size%sizeof(Elf32_Rel)||size>16*1024*1024)throw std::runtime_error("Tabela REL inválida");
        for(uint32_t i=0;i<size;i+=sizeof(Elf32_Rel)){
            Elf32_Rel rel;check(uc_mem_read(cpu.uc,address(offset+i),&rel,sizeof rel));
            uint32_t type=ELF32_R_TYPE(rel.r_info),index=ELF32_R_SYM(rel.r_info);
            if(type==R_ARM_NONE)continue;
            uint32_t where=address(rel.r_offset),addend=cpu.word(where),value=0;
            if(type==R_ARM_RELATIVE){cpu.put(where,address(addend));relocations++;continue;}
            if(type!=R_ARM_ABS32&&type!=R_ARM_REL32&&type!=R_ARM_GLOB_DAT&&type!=R_ARM_JUMP_SLOT)
                throw std::runtime_error("Relocação ARM ainda sem suporte: "+std::to_string(type));
            if(index>=symbols.size())throw std::runtime_error("Índice de símbolo inválido");
            auto sym=symbols[index];
            if(sym.st_shndx!=SHN_UNDEF)value=sym.st_shndx==SHN_ABS?sym.st_value:address(sym.st_value);
            else {
                std::string name=string(sym.st_name);imports.insert(name);
                value=ELF32_ST_BIND(sym.st_info)==STB_WEAK?0:cpu.trap(name);
            }
            if(type==R_ARM_ABS32)value+=addend;
            else if(type==R_ARM_REL32)value+=addend-where;
            cpu.put(where,value);relocations++;
        }
    }
    explicit Elf(const std::string& path){
        std::ifstream file(path,std::ios::binary|std::ios::ate);auto size=file.tellg();
        if(!file||size<52||size>128*1024*1024)throw std::runtime_error("Biblioteca ausente, pequena ou acima de 128 MiB");
        bytes.resize(size_t(size));file.seekg(0);if(!file.read((char*)bytes.data(),size))throw std::runtime_error("Leitura ELF falhou");
        auto h=at<Elf32_Ehdr>(0);
        if(std::memcmp(h.e_ident,ELFMAG,SELFMAG)||h.e_ident[EI_CLASS]!=ELFCLASS32||h.e_ident[EI_DATA]!=ELFDATA2LSB||h.e_machine!=EM_ARM||h.e_type!=ET_DYN)
            throw std::runtime_error("Esperado ELF32 ARM little-endian compartilhado");
        if(h.e_phentsize!=sizeof(Elf32_Phdr)||h.e_phnum==0||h.e_phnum>128)throw std::runtime_error("Program headers inválidos");
        uint32_t top=0;std::vector<Elf32_Phdr> loads;Elf32_Phdr dynamic{};
        for(unsigned i=0;i<h.e_phnum;i++){
            auto p=at<Elf32_Phdr>(uint64_t(h.e_phoff)+i*sizeof(Elf32_Phdr));
            if(p.p_type==PT_LOAD){
                if(p.p_filesz>p.p_memsz||uint64_t(p.p_vaddr)+p.p_memsz>0x08000000||uint64_t(p.p_offset)+p.p_filesz>bytes.size())throw std::runtime_error("Segmento ELF inválido");
                top=std::max(top,p.p_vaddr+p.p_memsz);loads.push_back(p);
            }else if(p.p_type==PT_DYNAMIC)dynamic=p;
        }
        if(top==0||dynamic.p_memsz==0||dynamic.p_memsz>1024*1024||dynamic.p_memsz%sizeof(Elf32_Dyn))throw std::runtime_error("ELF sem segmentos dinâmicos válidos");
        cpu.setup();check(uc_mem_map(cpu.uc,BASE,(uint64_t(top)+4095)&~uint64_t(4095),UC_PROT_ALL));
        for(auto p:loads)if(p.p_filesz)cpu.write(address(p.p_vaddr),bytes.data()+p.p_offset,p.p_filesz);
        std::vector<uint32_t> deps;bool ended=false;
        for(uint32_t i=0;i<dynamic.p_memsz;i+=sizeof(Elf32_Dyn)){
            Elf32_Dyn d;check(uc_mem_read(cpu.uc,address(dynamic.p_vaddr+i),&d,sizeof d));
            if(d.d_tag==DT_NULL){ended=true;break;}
            if(d.d_tag==DT_NEEDED)deps.push_back(d.d_un.d_val);else tags[d.d_tag]=d.d_un.d_val;
        }
        if(!ended)throw std::runtime_error("Tabela dinâmica sem terminador");
        if(tags.count(0x6000000f)||tags.count(0x60000011)||tags.count(DT_RELA))throw std::runtime_error("Relocações APS2/RELA ainda não implementadas");
        if(!tags.count(DT_HASH)||!tags.count(DT_SYMTAB)||tags[DT_SYMENT]!=sizeof(Elf32_Sym))throw std::runtime_error("Tabela de símbolos SysV necessária neste protótipo");
        uint32_t count=cpu.word(address(tags.at(DT_HASH)+4));if(count>1000000)throw std::runtime_error("Excesso de símbolos ELF");
        for(uint32_t i=0;i<count;i++){
            Elf32_Sym s;check(uc_mem_read(cpu.uc,address(tags.at(DT_SYMTAB)+i*sizeof s),&s,sizeof s));symbols.push_back(s);
            if(s.st_shndx!=SHN_UNDEF&&s.st_name&&ELF32_ST_TYPE(s.st_info)==STT_FUNC)exports[string(s.st_name)]=address(s.st_value);
        }
        for(auto d:deps)needed.push_back(string(d));
        if(tags.count(DT_REL)){if(tags[DT_RELENT]!=sizeof(Elf32_Rel))throw std::runtime_error("RELENT inválido");relocate(tags.at(DT_REL),tags[DT_RELSZ]);}
        if(tags.count(DT_JMPREL)){if(tags[DT_PLTREL]!=DT_REL)throw std::runtime_error("PLT não usa REL");relocate(tags.at(DT_JMPREL),tags[DT_PLTRELSZ]);}
    }
    std::string runVersion(){
        auto it=exports.find("opus_get_version_string");if(it==exports.end())return "Consulta Opus ausente.\n";
        uint32_t ptr=cpu.call(it->second);std::string out;
        for(unsigned i=0;i<256;i++){char c;check(uc_mem_read(cpu.uc,ptr+i,&c,1));if(!c)return "Código real do APK executado: "+out+"\n";if(c<32||c>126)throw std::runtime_error("Resposta Opus inválida");out+=c;}
        throw std::runtime_error("Resposta Opus sem terminador");
    }
    std::string runJni(){
        auto it=exports.find("JNI_OnLoad");if(it==exports.end())return "JNI_OnLoad ausente.\n";
        cpu.put(VM,VM+0x100);
        const char* names[]={"reserved0","reserved1","reserved2","DestroyJavaVM","AttachCurrentThread","DetachCurrentThread","GetEnv","AttachCurrentThreadAsDaemon"};
        for(unsigned i=0;i<8;i++)cpu.put(VM+0x100+i*4,cpu.trap(std::string("JavaVM.")+names[i]+" (JNI Android de 32 bits)"));
        try{uint32_t r=cpu.call(it->second,VM,0);return "JNI_OnLoad retornou "+std::to_string(r)+"; inicialização completa não testada.\n";}
        catch(const std::exception& e){return std::string("Inicialização interrompida: ")+e.what()+"\n";}
    }
};
std::string inspect(const std::string& path,bool jni){
    Elf elf(path);std::ostringstream out;out<<"ELF32 carregado em memória emulada; "<<elf.relocations<<" relocações.\nDependências: ";
    for(auto& n:elf.needed)out<<n<<" ";
    out<<"\nImports externos sem ponte: "<<elf.imports.size()<<"\n";
    out<<(jni?elf.runJni():elf.runVersion());return out.str();
}
}
#ifdef __ANDROID__
extern "C" JNIEXPORT jstring JNICALL Java_com_nexa_arm32_NativeProbe_selfTest(JNIEnv* env,jclass){
    try{return env->NewStringUTF(selfTest().c_str());}catch(const std::exception& e){return env->NewStringUTF((std::string("FALHA CPU: ")+e.what()).c_str());}
}
extern "C" JNIEXPORT jstring JNICALL Java_com_nexa_arm32_NativeProbe_inspect(JNIEnv* env,jclass,jstring path,jboolean jni){
    const char* p=env->GetStringUTFChars(path,nullptr);if(!p)return nullptr;std::string file(p);env->ReleaseStringUTFChars(path,p);
    try{return env->NewStringUTF(inspect(file,jni).c_str());}catch(const std::exception& e){return env->NewStringUTF((std::string("FALHA ELF: ")+e.what()).c_str());}
}
#else
#include <iostream>
int main(int argc,char** argv){try{std::cout<<selfTest();if(argc>1)std::cout<<inspect(argv[1],argc>2);return 0;}catch(const std::exception& e){std::cerr<<e.what()<<"\n";return 1;}}
#endif
