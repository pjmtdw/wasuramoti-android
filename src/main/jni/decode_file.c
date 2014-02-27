/********************************************************************
 *                                                                  *
 * THIS FILE IS PART OF THE OggVorbis SOFTWARE CODEC SOURCE CODE.   *
 * USE, DISTRIBUTION AND REPRODUCTION OF THIS LIBRARY SOURCE IS     *
 * GOVERNED BY A BSD-STYLE SOURCE LICENSE INCLUDED WITH THIS SOURCE *
 * IN 'COPYING'. PLEASE READ THESE TERMS BEFORE DISTRIBUTING.       *
 *                                                                  *
 * THE OggVorbis SOURCE CODE IS (C) COPYRIGHT 1994-2009             *
 * by the Xiph.Org Foundation http://www.xiph.org/                  *
 *                                                                  *
 ********************************************************************

 function: simple example decoder
 last mod: $Id: decoder_example.c 16243 2009-07-10 02:49:31Z xiphmont $

 ********************************************************************/

/* Takes a vorbis bitstream from fin and writes raw stereo PCM to
   fout. Decodes simple and chained OggVorbis files from beginning
   to end. Vorbisfile.a is somewhat more complex than the code below.  */

/* Note that this is POSIX, not ANSI code */

#include <stdio.h>
#include <stdlib.h>
#include <math.h>
#include <vorbis/codec.h>

#include <android/log.h>
#include "wav_ogg_file_codec_jni.h"

#ifdef _WIN32 /* We need the following two to set fin/fout to binary */
#include <io.h>
#include <fcntl.h>
#endif

#if defined(__MACOS__) && defined(__MWERKS__)
#include <console.h>      /* CodeWarrior's Mac "command-line" support */
#endif

#define BIT_DEPTH 16
#define MAX_AMPLITUDE ((1 << (BIT_DEPTH-1)) - 1)

ogg_int16_t convbuffer[4096]; /* take 8k out of the data segment, not the stack */
int convsize=4096;

extern void _VDBG_dump(void);

int decode_file(const char* fin_path, const char * fout_path, struct wav_ogg_file_codec_info * return_info){
  ogg_sync_state   oy; /* sync and verify incoming physical bitstream */
  ogg_stream_state os; /* take physical pages, weld into a logical
                          stream of packets */
  ogg_page         og; /* one Ogg bitstream page. Vorbis packets are inside */
  ogg_packet       op; /* one raw packet of data for decode */

  vorbis_info      vi; /* struct that stores all the static vorbis bitstream
                          settings */
  vorbis_comment   vc; /* struct that stores all the bitstream user comments */
  vorbis_dsp_state vd; /* central working state for the packet->PCM decoder */
  vorbis_block     vb; /* local working space for packet->PCM decode */

  char *buffer;
  int  bytes;

  FILE *fin, *fout;
  if (!(fin = fopen(fin_path, "rb"))){
    __android_log_print(ANDROID_LOG_INFO,"wasuramoti","cannot read file: %s",fin_path);
    return(0);
  }

  if (!(fout = fopen(fout_path,"wb"))){
    __android_log_print(ANDROID_LOG_INFO,"wasuramoti","cannot write file: %s",fout_path);
    fclose(fin);
    return(0);
  }

#ifdef _WIN32 /* We need to set fin/fout to binary mode. Damn windows. */
  /* Beware the evil ifdef. We avoid these where we can, but this one we
     cannot. Don't add any more, you'll probably go to hell if you do. */
//  _setmode( _fileno( stdin ), _O_BINARY );
//  _setmode( _fileno( stdout ), _O_BINARY );
#endif

#if defined(macintosh) && defined(__MWERKS__)
  {
    int argc;
    char **argv;
    argc=ccommand(&argv); /* get a "command line" from the Mac user */
                     /* this also lets the user set fin and fout */
  }
#endif

  /********** Decode setup ************/

  ogg_sync_init(&oy); /* Now we can read pages */
  
  while(1){ /* we repeat if the bitstream is chained */
    int eos=0;
    int i;

    /* grab some data at the head of the stream. We want the first page
       (which is guaranteed to be small and only contain the Vorbis
       stream initial header) We need the first page to get the stream
       serialno. */

    /* submit a 4k block to libvorbis' Ogg layer */
    buffer=ogg_sync_buffer(&oy,4096);
    bytes=fread(buffer,1,4096,fin);
    ogg_sync_wrote(&oy,bytes);
    
    /* Get the first page. */
    if(ogg_sync_pageout(&oy,&og)!=1){
      /* have we simply run out of data?  If so, we're done. */
      if(bytes<4096)break;
      
      /* error case.  Must not be Vorbis data */
      __android_log_print(ANDROID_LOG_INFO,"wasuramoti","Input does not appear to be an Ogg bitstream.\n");
      return(0);
    }
  
    /* Get the serial number and set up the rest of decode. */
    /* serialno first; use it to set up a logical stream */
    ogg_stream_init(&os,ogg_page_serialno(&og));
    
    /* extract the initial header from the first page and verify that the
       Ogg bitstream is in fact Vorbis data */
    
    /* I handle the initial header first instead of just having the code
       read all three Vorbis headers at once because reading the initial
       header is an easy way to identify a Vorbis bitstream and it's
       useful to see that functionality seperated out. */
    
    vorbis_info_init(&vi);
    vorbis_comment_init(&vc);
    if(ogg_stream_pagein(&os,&og)<0){ 
      /* error; stream version mismatch perhaps */
      __android_log_print(ANDROID_LOG_INFO,"wasuramoti","Error reading first page of Ogg bitstream data.\n");
      return(0);
    }
    
    if(ogg_stream_packetout(&os,&op)!=1){ 
      /* no page? must not be vorbis */
      __android_log_print(ANDROID_LOG_INFO,"wasuramoti","Error reading initial header packet.\n");
      return(0);
    }
    
    if(vorbis_synthesis_headerin(&vi,&vc,&op)<0){ 
      /* error case; not a vorbis header */
      __android_log_print(ANDROID_LOG_INFO,"wasuramoti","This Ogg bitstream does not contain Vorbis "
              "audio data.\n");
      return(0);
    }
    return_info->channels = vi.channels;
    return_info->rate = vi.rate;
    return_info->bit_depth = BIT_DEPTH;
    
    /* At this point, we're sure we're Vorbis. We've set up the logical
       (Ogg) bitstream decoder. Get the comment and codebook headers and
       set up the Vorbis decoder */
    
    /* The next two packets in order are the comment and codebook headers.
       They're likely large and may span multiple pages. Thus we read
       and submit data until we get our two packets, watching that no
       pages are missing. If a page is missing, error out; losing a
       header page is the only place where missing data is fatal. */
    
    i=0;
    while(i<2){
      while(i<2){
        int result=ogg_sync_pageout(&oy,&og);
        if(result==0)break; /* Need more data */
        /* Don't complain about missing or corrupt data yet. We'll
           catch it at the packet output phase */
        if(result==1){
          ogg_stream_pagein(&os,&og); /* we can ignore any errors here
                                         as they'll also become apparent
                                         at packetout */
          while(i<2){
            result=ogg_stream_packetout(&os,&op);
            if(result==0)break;
            if(result<0){
              /* Uh oh; data at some point was corrupted or missing!
                 We can't tolerate that in a header.  Die. */
              __android_log_print(ANDROID_LOG_INFO,"wasuramoti","Corrupt secondary header.  Exiting.\n");
              return(0);
            }
            result=vorbis_synthesis_headerin(&vi,&vc,&op);
            if(result<0){
              __android_log_print(ANDROID_LOG_INFO,"wasuramoti","Corrupt secondary header.  Exiting.\n");
              return(0);
            }
            i++;
          }
        }
      }
      /* no harm in not checking before adding more */
      buffer=ogg_sync_buffer(&oy,4096);
      bytes=fread(buffer,1,4096,fin);
      if(bytes==0 && i<2){
        __android_log_print(ANDROID_LOG_INFO,"wasuramoti","End of file before finding all Vorbis headers!\n");
        return(0);
      }
      ogg_sync_wrote(&oy,bytes);
    }
    
    /* Throw the comments plus a few lines about the bitstream we're
       decoding */
    {
      /*
      char **ptr=vc.user_comments;
      while(*ptr){
        __android_log_print(ANDROID_LOG_INFO,"wasuramoti","%s\n",*ptr);
        ++ptr;
      }
      */
      /*
      __android_log_print(ANDROID_LOG_INFO,"wasuramoti","Bitstream is %d channel, %ldHz\n",vi.channels,vi.rate);
      __android_log_print(ANDROID_LOG_INFO,"wasuramoti","Encoded by: %s\n",vc.vendor);
      */
    }
    
    convsize=4096/vi.channels;

    /* OK, got and parsed all three headers. Initialize the Vorbis
       packet->PCM decoder. */
    if(vorbis_synthesis_init(&vd,&vi)==0){ /* central decode state */
      vorbis_block_init(&vd,&vb);          /* local state for most of the decode
                                              so multiple block decodes can
                                              proceed in parallel. We could init
                                              multiple vorbis_block structures
                                              for vd here */
      
      /* The rest is just a straight decode loop until end of stream */
      while(!eos){
        while(!eos){
          int result=ogg_sync_pageout(&oy,&og);
          if(result==0)break; /* need more data */
          if(result<0){ /* missing or corrupt data at this page position */
            __android_log_print(ANDROID_LOG_INFO,"wasuramoti","Corrupt or missing data in bitstream; "
                    "continuing...\n");
          }else{
            ogg_stream_pagein(&os,&og); /* can safely ignore errors at
                                           this point */
            while(1){
              result=ogg_stream_packetout(&os,&op);
              
              if(result==0)break; /* need more data */
              if(result<0){ /* missing or corrupt data at this page position */
                /* no reason to complain; already complained above */
              }else{
                /* we have a packet.  Decode it */
                float **pcm;
                int samples;
                
                if(vorbis_synthesis(&vb,&op)==0) /* test for success! */
                  vorbis_synthesis_blockin(&vd,&vb);
                /* 
                   
                **pcm is a multichannel float vector.  In stereo, for
                example, pcm[0] is left, and pcm[1] is right.  samples is
                the size of each channel.  Convert the float values
                (-1.<=range<=1.) to whatever PCM format and write it out */
                
                while((samples=vorbis_synthesis_pcmout(&vd,&pcm))>0){
                  int j;
                  int clipflag=0;
                  int bout=(samples<convsize?samples:convsize);
                  
                  /* convert floats to 16 bit signed ints (host order) and
                     interleave */
                  for(i=0;i<vi.channels;i++){
                    ogg_int16_t *ptr=convbuffer+i;
                    float  *mono=pcm[i];
                    for(j=0;j<bout;j++){
#if 1
                      int val=floor(mono[j]*(float)MAX_AMPLITUDE+.5f);
#else /* optional dither */
                      int val=mono[j]*(float)MAX_AMPLITUDE+drand48()-0.5f;
#endif
                      /* might as well guard against clipping */
                      if(val>MAX_AMPLITUDE){
                        val=MAX_AMPLITUDE;
                        clipflag=1;
                      }
                      if(val<-MAX_AMPLITUDE-1){
                        val=-MAX_AMPLITUDE-1;
                        clipflag=1;
                      }
                      *ptr=val;
                      ptr+=vi.channels;
                    }
                  }
                  
                  if(clipflag)
                    __android_log_print(ANDROID_LOG_INFO,"wasuramoti","Clipping in frame %ld\n",(long)(vd.sequence));
                  
                  
                  fwrite(convbuffer,2*vi.channels,bout,fout);
                  
                  vorbis_synthesis_read(&vd,bout); /* tell libvorbis how
                                                      many samples we
                                                      actually consumed */
                }            
              }
            }
            if(ogg_page_eos(&og))eos=1;
          }
        }
        if(!eos){
          buffer=ogg_sync_buffer(&oy,4096);
          bytes=fread(buffer,1,4096,fin);
          ogg_sync_wrote(&oy,bytes);
          if(bytes==0)eos=1;
        }
      }
      
      /* ogg_page and ogg_packet structs always point to storage in
         libvorbis.  They're never freed or manipulated directly */
      
      vorbis_block_clear(&vb);
      vorbis_dsp_clear(&vd);
    }else{
      __android_log_print(ANDROID_LOG_INFO,"wasuramoti","Error: Corrupt header during playback initialization.\n");
    }

    /* clean up this logical bitstream; before exit we see if we're
       followed by another [chained] */
    
    ogg_stream_clear(&os);
    vorbis_comment_clear(&vc);
    vorbis_info_clear(&vi);  /* must be called last */
  }

  /* OK, clean up the framer */
  ogg_sync_clear(&oy);
  
  fclose(fin);
  fclose(fout);
  /*__android_log_print(ANDROID_LOG_INFO,"wasuramoti","Ogg Decode Done.\n"); */
  return(1);
}
