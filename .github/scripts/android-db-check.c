#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <inttypes.h>
#include <math.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* SQLite opaque declarations: deliberately independent of sqlite headers. */
typedef struct sqlite3 sqlite3;
typedef struct sqlite3_stmt sqlite3_stmt;
enum { SQLITE_OK = 0, SQLITE_ROW = 100, SQLITE_DONE = 101,
       SQLITE_INTEGER = 1, SQLITE_FLOAT = 2, SQLITE_TEXT = 3,
       SQLITE_BLOB = 4, SQLITE_NULL = 5, SQLITE_OPEN_READONLY = 0x00000001 };

typedef int (*open_v2_fn)(const char *, sqlite3 **, int, const char *);
typedef int (*close_v2_fn)(sqlite3 *);
typedef int (*prepare_v2_fn)(sqlite3 *, const char *, int, sqlite3_stmt **, const char **);
typedef int (*step_fn)(sqlite3_stmt *);
typedef int (*finalize_fn)(sqlite3_stmt *);
typedef int (*column_count_fn)(sqlite3_stmt *);
typedef const char *(*column_name_fn)(sqlite3_stmt *, int);
typedef int (*column_type_fn)(sqlite3_stmt *, int);
typedef const unsigned char *(*column_text_fn)(sqlite3_stmt *, int);
typedef const void *(*column_blob_fn)(sqlite3_stmt *, int);
typedef int (*column_bytes_fn)(sqlite3_stmt *, int);
typedef int64_t (*column_int64_fn)(sqlite3_stmt *, int);
typedef double (*column_double_fn)(sqlite3_stmt *, int);
typedef const char *(*errmsg_fn)(sqlite3 *);
typedef const char *(*libversion_fn)(void);
typedef int (*enable_load_extension_fn)(sqlite3 *, int);
typedef int (*load_extension_fn)(sqlite3 *, const char *, const char *, char **);
typedef void (*free_fn)(void *);

static open_v2_fn p_open_v2;
static close_v2_fn p_close_v2;
static prepare_v2_fn p_prepare_v2;
static step_fn p_step;
static finalize_fn p_finalize;
static column_count_fn p_column_count;
static column_name_fn p_column_name;
static column_type_fn p_column_type;
static column_text_fn p_column_text;
static column_blob_fn p_column_blob;
static column_bytes_fn p_column_bytes;
static column_int64_fn p_column_int64;
static column_double_fn p_column_double;
static errmsg_fn p_errmsg;
static libversion_fn p_libversion;
static enable_load_extension_fn p_enable_load_extension;
static load_extension_fn p_load_extension;
static free_fn p_sqlite_free;

static char error_text[1024];
static void fail(const char *fmt, ...) {
    va_list ap; va_start(ap, fmt); vsnprintf(error_text, sizeof(error_text), fmt, ap); va_end(ap);
}

#define LOAD_SYM(handle, var, symbol) do { \
    *(void **)(&(var)) = dlsym((handle), (symbol)); \
    if (!(var)) { fail("missing SQLite API symbol: %s", (symbol)); return 0; } \
} while (0)
static int load_api(void *h) {
    LOAD_SYM(h,p_open_v2,"sqlite3_open_v2"); LOAD_SYM(h,p_close_v2,"sqlite3_close_v2");
    LOAD_SYM(h,p_prepare_v2,"sqlite3_prepare_v2"); LOAD_SYM(h,p_step,"sqlite3_step");
    LOAD_SYM(h,p_finalize,"sqlite3_finalize"); LOAD_SYM(h,p_column_count,"sqlite3_column_count");
    LOAD_SYM(h,p_column_name,"sqlite3_column_name"); LOAD_SYM(h,p_column_type,"sqlite3_column_type");
    LOAD_SYM(h,p_column_text,"sqlite3_column_text"); LOAD_SYM(h,p_column_blob,"sqlite3_column_blob");
    LOAD_SYM(h,p_column_bytes,"sqlite3_column_bytes"); LOAD_SYM(h,p_column_int64,"sqlite3_column_int64");
    LOAD_SYM(h,p_column_double,"sqlite3_column_double"); LOAD_SYM(h,p_errmsg,"sqlite3_errmsg");
    LOAD_SYM(h,p_libversion,"sqlite3_libversion"); LOAD_SYM(h,p_enable_load_extension,"sqlite3_enable_load_extension");
    LOAD_SYM(h,p_load_extension,"sqlite3_load_extension"); LOAD_SYM(h,p_sqlite_free,"sqlite3_free");
    return 1;
}

typedef struct { char *s; size_t n, cap; } Buffer;
static int reserve(Buffer *b, size_t extra) {
    if (extra > SIZE_MAX - b->n - 1) return 0;
    size_t need = b->n + extra + 1;
    if (need <= b->cap) return 1;
    size_t cap = b->cap ? b->cap : 4096;
    while (cap < need) { if (cap > SIZE_MAX/2) { cap=need; break; } cap*=2; }
    char *p = (char *)realloc(b->s, cap); if (!p) return 0; b->s=p; b->cap=cap; return 1;
}
static int append_n(Buffer *b, const char *s, size_t n) {
    if (!reserve(b,n)) return 0; memcpy(b->s+b->n,s,n); b->n+=n; b->s[b->n]=0; return 1;
}
static int append(Buffer *b, const char *s) { return append_n(b,s,strlen(s)); }
static int appendf(Buffer *b, const char *fmt, ...) {
    va_list ap, cp; va_start(ap,fmt); va_copy(cp,ap); int n=vsnprintf(NULL,0,fmt,cp); va_end(cp);
    if (n<0 || !reserve(b,(size_t)n)) { va_end(ap); return 0; }
    vsnprintf(b->s+b->n,b->cap-b->n,fmt,ap); va_end(ap); b->n+=(size_t)n; return 1;
}
static int json_string_n(Buffer *b, const unsigned char *s, size_t n) {
    if (!append(b,"\"")) return 0;
    for (size_t i=0;i<n;i++) {
        unsigned char c=s[i];
        if (c=='"' || c=='\\') { char x[2]={'\\',(char)c}; if(!append_n(b,x,2)) return 0; }
        else if (c<0x20) { if(!appendf(b,"\\u%04x",(unsigned)c)) return 0; }
        else if (!append_n(b,(const char *)&s[i],1)) return 0;
    }
    return append(b,"\"");
}
static int json_string(Buffer *b, const char *s) { return json_string_n(b,(const unsigned char *)(s?s:""),strlen(s?s:"")); }
static int sql_query(sqlite3 *db, const char *sql, sqlite3_stmt **out) {
    int rc=p_prepare_v2(db,sql,-1,out,NULL);
    if(rc!=SQLITE_OK) { fail("prepare failed (%d): %s | SQL: %.180s",rc,p_errmsg(db),sql); return 0; }
    return 1;
}
static int finish_stmt(sqlite3_stmt *st, int rc, const char *what) {
    int frc=p_finalize(st);
    if (rc!=SQLITE_DONE) { fail("%s step failed (%d)",what,rc); return 0; }
    if (frc!=SQLITE_OK) { fail("%s finalize failed (%d)",what,frc); return 0; }
    return 1;
}
static int emit_value(Buffer *b, sqlite3_stmt *st, int col) {
    int t=p_column_type(st,col);
    if(t==SQLITE_NULL) return append(b,"null");
    if(t==SQLITE_INTEGER) return appendf(b,"%" PRId64,p_column_int64(st,col));
    if(t==SQLITE_FLOAT) { double d=p_column_double(st,col); if(!isfinite(d)) return append(b,"null"); return appendf(b,"%.17g",d); }
    int n=p_column_bytes(st,col); if(n<0) return 0;
    if(t==SQLITE_BLOB) {
        const unsigned char *p=(const unsigned char *)p_column_blob(st,col);
        if(!append(b,"{\"$blob_hex\":\"")) return 0;
        if(n && !p) return 0;
        for(int i=0;i<n;i++) if(!appendf(b,"%02x",p[i])) return 0;
        return append(b,"\"}");
    }
    const unsigned char *p=p_column_text(st,col);
    if(n && !p) return 0;
    return json_string_n(b,p?p:(const unsigned char *)"",(size_t)n);
}
typedef struct { char **v; size_t n,cap; } Names;
static int names_add(Names *a,const unsigned char *p,size_t n) {
    if(a->n==a->cap) { size_t c=a->cap?a->cap*2:32; char **v=(char **)realloc(a->v,c*sizeof(char *)); if(!v)return 0; a->v=v;a->cap=c; }
    char *s=(char *)malloc(n+1); if(!s)return 0; memcpy(s,p,n);s[n]=0;a->v[a->n++]=s;return 1;
}
static char *quote_ident(const char *s) {
    size_t n=strlen(s), q=2; for(size_t i=0;i<n;i++) if(s[i]=='"')q++;
    char *r=(char *)malloc(q+n+1); if(!r)return NULL; size_t j=0;r[j++]='"';
    for(size_t i=0;i<n;i++){r[j++]=s[i];if(s[i]=='"')r[j++]='"';}r[j++]='"';r[j]=0;return r;
}
static int emit_rows_as_values(Buffer *b, sqlite3 *db, const char *sql, const char *label) {
    sqlite3_stmt *st=NULL; if(!sql_query(db,sql,&st))return 0;
    if(!append(b,"[")){p_finalize(st);return 0;} int first=1,rc;
    while((rc=p_step(st))==SQLITE_ROW) {
        if(!first&&!append(b,",")){p_finalize(st);return 0;} first=0;
        if(!emit_value(b,st,0)){p_finalize(st);return 0;}
    }
    return finish_stmt(st,rc,label) && append(b,"]");
}
static int run(sqlite3 *db, Buffer *out) {
    sqlite3_stmt *st=NULL;
    if(!append(out,"{\"sqlite_version\":"))return 0;
    if(!json_string(out,p_libversion())||!append(out,",\"user_version\":"))return 0;
    if(!sql_query(db,"PRAGMA user_version",&st))return 0;
    int rc=p_step(st); if(rc!=SQLITE_ROW){p_finalize(st);fail("PRAGMA user_version failed (%d)",rc);return 0;}
    if(!appendf(out,"%" PRId64,p_column_int64(st,0))){p_finalize(st);return 0;}
    rc=p_step(st); if(!finish_stmt(st,rc,"PRAGMA user_version"))return 0;
    if(!append(out,",\"integrity_check\":"))return 0;
    if(!emit_rows_as_values(out,db,"PRAGMA integrity_check","integrity_check"))return 0;
    if(!append(out,",\"foreign_key_check\":"))return 0;
    if(!sql_query(db,"PRAGMA foreign_key_check",&st))return 0;
    if(!append(out,"[")){p_finalize(st);return 0;} int first=1;
    while((rc=p_step(st))==SQLITE_ROW) {
        if(!first&&!append(out,",")){p_finalize(st);return 0;} first=0;
        if(!append(out,"{\"table\":")){p_finalize(st);return 0;}
        const unsigned char *txt=p_column_text(st,0);int nb=p_column_bytes(st,0);
        if(!json_string_n(out,txt?txt:(const unsigned char *)"",nb<0?0:(size_t)nb)||!append(out,",\"rowid\":" )||!emit_value(out,st,1)||!append(out,",\"parent\":")){p_finalize(st);return 0;}
        txt=p_column_text(st,2);nb=p_column_bytes(st,2);
        if(!json_string_n(out,txt?txt:(const unsigned char *)"",nb<0?0:(size_t)nb)||!append(out,",\"fkid\":" )||!emit_value(out,st,3)||!append(out,"}")){p_finalize(st);return 0;}
    }
    if(!finish_stmt(st,rc,"foreign_key_check")||!append(out,"],\"tables\":{"))return 0;
    Names names={0};
    if(!sql_query(db,"SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name",&st))return 0;
    while((rc=p_step(st))==SQLITE_ROW) {
        const unsigned char *p=p_column_text(st,0);int n=p_column_bytes(st,0);
        if(n<0||!p||!names_add(&names,p,(size_t)n)){fail("out of memory reading table names");p_finalize(st);goto bad;}
    }
    if(!finish_stmt(st,rc,"table enumeration"))goto bad;
    for(size_t i=0;i<names.n;i++) {
        if(i&&!append(out,","))goto bad;
        if(!json_string(out,names.v[i])||!append(out,":"))goto bad;
        char *qi=quote_ident(names.v[i]); if(!qi){fail("out of memory quoting table identifier");goto bad;}
        size_t sl=strlen(qi)+16; char *sql=(char *)malloc(sl); if(!sql){free(qi);fail("out of memory building SELECT");goto bad;}
        snprintf(sql,sl,"SELECT * FROM %s",qi);free(qi);
        if(!sql_query(db,sql,&st)){free(sql);goto bad;} free(sql);
        if(!append(out,"[")){p_finalize(st);goto bad;} int rowfirst=1;
        while((rc=p_step(st))==SQLITE_ROW) {
            if(!rowfirst&&!append(out,",")){p_finalize(st);goto bad;}rowfirst=0;
            if(!append(out,"{")){p_finalize(st);goto bad;}
            int cols=p_column_count(st);
            for(int c=0;c<cols;c++) {
                if(c&&!append(out,",")){p_finalize(st);goto bad;}
                if(!json_string(out,p_column_name(st,c))||!append(out,":")||!emit_value(out,st,c)){p_finalize(st);goto bad;}
            }
            if(!append(out,"}")){p_finalize(st);goto bad;}
        }
        if(!finish_stmt(st,rc,names.v[i]))goto bad;
        if(!append(out,"]"))goto bad;
    }
    for(size_t i=0;i<names.n;i++)free(names.v[i]);free(names.v);
    return append(out,"}}");
bad:
    for(size_t i=0;i<names.n;i++)free(names.v[i]);free(names.v);return 0;
}
int main(int argc,char **argv) {
    if(argc!=4){fprintf(stderr,"usage: %s <libsqlite3x.so> <libsimple.so> <dbpath>\n",argv[0]);return 2;}
    void *h=dlopen(argv[1],RTLD_NOW|RTLD_LOCAL);
    if(!h){fprintf(stderr,"dlopen SQLite failed: %s\n",dlerror());return 1;}
    if(!load_api(h)){fprintf(stderr,"%s\n",error_text);dlclose(h);return 1;}
    sqlite3 *db=NULL; int rc=p_open_v2(argv[3],&db,SQLITE_OPEN_READONLY,NULL);
    if(rc!=SQLITE_OK){fprintf(stderr,"sqlite3_open_v2 readonly failed (%d): %s\n",rc,db?p_errmsg(db):"no database handle");if(db)p_close_v2(db);dlclose(h);return 1;}
    if(p_enable_load_extension(db,1)!=SQLITE_OK){fprintf(stderr,"enable_load_extension failed: %s\n",p_errmsg(db));p_close_v2(db);dlclose(h);return 1;}
    char *ext_err=NULL; rc=p_load_extension(db,argv[2],"sqlite3_simple_init",&ext_err);
    int disable_rc=p_enable_load_extension(db,0);
    if(rc!=SQLITE_OK){fprintf(stderr,"load_extension sqlite3_simple_init failed (%d): %s\n",rc,ext_err?ext_err:p_errmsg(db));if(ext_err)p_sqlite_free(ext_err);p_close_v2(db);dlclose(h);return 1;}
    if(ext_err)p_sqlite_free(ext_err);
    if(disable_rc!=SQLITE_OK){fprintf(stderr,"disable_load_extension failed: %s\n",p_errmsg(db));p_close_v2(db);dlclose(h);return 1;}
    Buffer output={0};
    if(!run(db,&output)){fprintf(stderr,"database check failed: %s\n",error_text[0]?error_text:"JSON allocation/serialization failure");free(output.s);p_close_v2(db);dlclose(h);return 1;}
    if(p_close_v2(db)!=SQLITE_OK){fprintf(stderr,"sqlite3_close_v2 failed\n");free(output.s);dlclose(h);return 1;}
    dlclose(h);
    if(fwrite(output.s,1,output.n,stdout)!=output.n||fputc('\n',stdout)==EOF){fprintf(stderr,"stdout write failed\n");free(output.s);return 1;}
    free(output.s);return 0;
}
