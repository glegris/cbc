CBC = ../bin/cbc
CBFLAGS = -O -fPIC
TARGET = libcbc.a
OBJS = stdarg.o alloca.o

CC = gcc
CFLAGS = -Wall
AR_CREATE = ar crs

.SUFFIXES:
.SUFFIXES: .c .s .o

.c.o:
	$(CBC) $(CBFLAGS) -Wa,"--32" -c $< -o $@
.s.o:
	$(CBC) -Wa,"--32" -c $<

$(TARGET): $(OBJS)
	$(AR_CREATE) $(TARGET) $(OBJS)

stdarg.o: stdarg.c

sizeof_jmpbuf: sizeof_jmpbuf.c
	$(CC) $(CFLAGS) -o $@ $<

clean:
	rm -f $(TARGET) *.o stdarg.s
