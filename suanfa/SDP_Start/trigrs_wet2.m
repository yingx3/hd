function [Phead,ZMAX,Fs]=trigrs_wet2(T,file_path,dem,slope,flowdirection,zmax,depthwt,Ys,Yw,c,f,Ks,Izlt,D0)
w1=dem.size(1);
w2=dem.size(2);
Dem=double(dem.Z);
slope=double(slope.Z);
flowdirection=double(flowdirection.Z);
zmax=double(zmax.Z);
depthwt=double(depthwt.Z);
Ys=double(Ys.Z);
Yw=double(Yw.Z);
c=double(c.Z);
c=c;
f=double(f.Z);
f=f;
Ks=double(Ks.Z);
Izlt=double(Izlt.Z);
D0=double(D0.Z);
depthwt=depthwt(:);
Kss=Ks;
Ks=Ks(:);
D0=D0(:);
c=c(:);
Ys=Ys(:);
Ys=Ys;
Yw=Yw(:);
zmax=zmax(:);
dlz=zmax; %基岩厚度
f=f(:);
Izlt=Izlt(:);
r=slope(:)*pi/180;%栅格
beta=cos(r).^2-(Izlt./Ks); %b代表β
D1=D0./cos(r).^2;
m=20;
t = textread('t.txt','%f');%每一次降雨事件的输入时间
[N,~]=size(t);
undem=sort(unique(Dem),'descend');
[n1,~]=size(undem);
Inz2=zeros(w1*w2,N-1);
for ss=1:N-1
    file = strcat( file_path, num2str(ss),'.tif');
    inz= GRIDobj(file);
    inz= double(inz.Z);
    Inz1= padarray(inz, [1 1]);
    for j=1:n1
        [c1,c2]=find(Dem==undem(j));
        [n2,~]=size(c1);
        for i=1:n2
            if Inz1(c1(i)+1,c2(i)+1)<Kss(c1(i),c2(i))
                Inz1(c1(i)+1,c2(i)+1)=Inz1(c1(i)+1,c2(i)+1);
            else
                Inz1(c1(i)+1,c2(i)+1)=Kss(c1(i),c2(i));
                if flowdirection(c1(i),c2(i))==1
                    Inz1(c1(i)+1,c2(i)+1+1)=Inz1(c1(i)+1,c2(i)+1+1)+Inz1(c1(i)+1,c2(i)+1)-Kss(c1(i),c2(i));
                elseif flowdirection(c1(i),c2(i))==2
                    Inz1(c1(i)+1+1,c2(i)+1+1)=Inz1(c1(i)+1+1,c2(i)+1+1)+Inz1(c1(i)+1,c2(i)+1)-Kss(c1(i),c2(i));
                elseif flowdirection(c1(i),c2(i))==4
                    Inz1(c1(i)+1+1,c2(i)+1)= Inz1(c1(i)+1+1,c2(i)+1)+Inz1(c1(i)+1,c2(i)+1)-Kss(c1(i),c2(i));
                elseif flowdirection(c1(i),c2(i))==8
                    Inz1(c1(i)+1+1,c2(i)-1+1)=Inz1(c1(i)+1+1,c2(i)-1+1)+Inz1(c1(i)+1,c2(i)+1)-Kss(c1(i),c2(i));
                elseif flowdirection(c1(i),c2(i))==16
                    Inz1(c1(i)+1,c2(i)-1+1)=Inz1(c1(i)+1,c2(i)-1+1)+Inz1(c1(i)+1,c2(i)+1)-Kss(c1(i),c2(i));
                elseif flowdirection(c1(i),c2(i))==32
                    Inz1(c1(i)-1+1,c2(i)-1+1)=Inz1(c1(i)-1+1,c2(i)-1+1)+Inz1(c1(i)+1,c2(i)+1)-Kss(c1(i),c2(i));
                elseif flowdirection(c1(i),c2(i))==64
                    Inz1(c1(i)-1+1,c2(i)+1)=Inz1(c1(i)-1+1,c2(i)+1)+Inz1(c1(i)+1,c2(i)+1)-Kss(c1(i),c2(i));
                elseif flowdirection(c1(i),c2(i))==128
                    Inz1(c1(i)-1+1,c2(i)+1+1)=Inz1(c1(i)-1+1,c2(i)+1+1)+Inz1(c1(i)+1,c2(i)+1)-Kss(c1(i),c2(i));
                end
            end
        end
    end
    Inz=Inz1(2:end-1,2:end-1) ;
    Inz2(:,ss)=Inz(:);
end

Zbin=11;
Z1=zeros(w1*w2,Zbin);
for k=1:w1*w2
    bin1=linspace(0,zmax(k),Zbin);
    Z1(k,:)=round(bin1*1000)/1000';
end
Z1(:,1)=0.005;
Pdepth=zeros(w1*w2,1);
Fs2=zeros(w1*w2,1);
for k=1:Zbin
    
    Z=Z1(:,k);
    Pzera=(Z-depthwt).*beta; %代表静态水头压力，即公式第一项
    
    Ptran1=zeros(w1*w2,1);
    Ptran2=zeros(w1*w2,1);
    Pbeta=Z.*beta;
    for i=1:N
        if T-t(i)>0
            Inz=Inz2(:,i);
            H=1;
            ff1=Inz./Ks;
            ss=zeros(w1*w2,1);
            for j=1:m
                ss1=((2*j-1)*dlz-(dlz-Z))./(2*sqrt(D1*(T-t(i))));
                ss2=((2*j-1)*dlz+(dlz-Z))./(2*sqrt(D1*(T-t(i))));
                ierfc11=(1/sqrt(pi))*exp(-ss1.^2)-ss1.*erfc(ss1);
                ierfc22=(1/sqrt(pi))*exp(-ss2.^2)-ss2.*erfc(ss2);
                ss=ierfc11+ierfc22+ss;
            end
            ff2=H*sqrt(D1*(T-t(i))).*ss;
            yy=2*ff1.*ff2;
            Ptran1=Ptran1+yy;
            
        else
            break;
        end
    end
    
    
    for i=1:N
        if T-t(i+1)>0
            Inz=Inz2(:,i);
            H1=1;
            ff22=Inz./Ks;
            ss=zeros(w1*w2,1);
            for j=1:m
                ss3=((2*j-1)*dlz-(dlz-Z))./(2*sqrt(D1*(T-t(i+1))));
                ss4=((2*j-1)*dlz+(dlz-Z))./(2*sqrt(D1*(T-t(i+1))));
                ierfc33=(1/sqrt(pi))*exp(-ss3.^2)-ss3.*erfc(ss3);
                ierfc44=(1/sqrt(pi))*exp(-ss4.^2)-ss4.*erfc(ss4);
                ss=ierfc33+ierfc44+ss;
            end
            ff3=H1*sqrt(D1*(T-t(i+1))).*ss;
            yy=2*ff22.*ff3;
            Ptran2=Ptran2+yy;
            
        else
            break;
        end
    end
    
    Ptran=Ptran1-Ptran2;
    PP=Ptran+Pzera;
    GW1=zeros(w1*w2,1);
    for jj=1:w1*w2
        if Pbeta(jj) <PP(jj)
            GW1(jj,:)=Pbeta(jj);
        elseif Pbeta(jj) >PP(jj)
            GW1(jj,:)=PP(jj);
        end
    end
    Fs1=tan(f*pi/180)./tan(r)+(c-GW1.*Yw.*tan(f*pi/180))./(Ys.*Z.*sin(r).*cos(r));
    Pdepth(:,k)=GW1;
    Fs2(:,k)=Fs1;
end


FS=min(Fs2')';
Phead=zeros(w1*w2,1);
Zz=zeros(w1*w2,1);

for i=1:w1*w2
    [~,c2]=find(Fs2(i,:)==FS(i));
    if isnan(FS(i))
        FS(i)=10;
    elseif FS(i)>10
        FS(i)=10;
        Phead(i,:)= Pdepth(i,1);
    else
        Phead(i,:)= Pdepth(i,c2);
        Zz(i,:)= Z1(i,c2);
    end
end


Phead=reshape(Phead,w1,w2);
ZMAX=reshape(Zz,w1,w2);
Fs=reshape(FS,w1,w2);

dem.Z=Phead;
GRIDobj2geotiff(dem,'C:\Users\DELL\Desktop\TRIGRSshuicheng\results\wet2_Phead');
dem.Z=Fs;
GRIDobj2geotiff(dem,'C:\Users\DELL\Desktop\TRIGRSshuicheng\results\wet2_Fs');
dem.Z=ZMAX;
GRIDobj2geotiff(dem,'C:\Users\DELL\Desktop\TRIGRSshuicheng\results\wet2_ZMAX11');




